"""Validate the vendor's original XLS without changing or deduplicating rows."""
from __future__ import annotations

import datetime as dt
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any, Iterable

REQUIRED_COLUMNS = ("商品名称", "出货数量", "设备ID", "商品金额(元)", "订单号", "出货时间")
XLS_MAGIC = bytes.fromhex("d0cf11e0a1b11ae1")


class SalesValidationError(ValueError):
    pass


def _verify_rows(
    rows: Iterable[dict[str, Any]], start_at: dt.datetime, end_at: dt.datetime,
    expected_rows: int,
) -> dict[str, Any]:
    if isinstance(expected_rows, bool) or not isinstance(expected_rows, int) or expected_rows < 0:
        raise SalesValidationError("无法确认网页查询结果条数")
    if start_at.tzinfo is None or end_at.tzinfo is None or end_at < start_at:
        raise SalesValidationError("核验时间范围无效")
    count = 0
    quantity = Decimal(0)
    amount = Decimal(0)
    orders: set[str] = set()
    first = last = None
    for row in rows:
        count += 1
        for name in ("商品名称", "设备ID", "订单号"):
            value = row.get(name)
            if not isinstance(value, str) or not value.strip():
                raise SalesValidationError(f"第 {count} 条明细缺少有效的{name}")
        try:
            value = row["出货时间"]
            if not isinstance(value, str):
                raise ValueError("Expected vendor timestamp text")
            when = dt.datetime.strptime(value.strip(), "%Y-%m-%d %H:%M:%S").replace(tzinfo=start_at.tzinfo)
            units = Decimal(str(row["出货数量"]))
            line_amount = Decimal(str(row["商品金额(元)"]))
            if not units.is_finite() or not line_amount.is_finite() or units != units.to_integral_value():
                raise ValueError("Invalid numeric value")
        except (KeyError, ValueError, TypeError, InvalidOperation) as exc:
            raise SalesValidationError(f"第 {count} 条明细缺少有效时间、数量或金额") from exc
        if not start_at <= when <= end_at:
            raise SalesValidationError(f"第 {count} 条明细不在请求日期范围内")
        first = when if first is None else min(first, when)
        last = when if last is None else max(last, when)
        quantity += units
        # The amount column is already the line subtotal, including its quantity.
        amount += line_amount
        orders.add(row["订单号"].strip())
    if count != expected_rows:
        raise SalesValidationError(f"文件明细 {count} 条与网页查询 {expected_rows} 条不一致")
    return {
        "rows": count,
        "orders": len(orders),
        "quantity": int(quantity),
        "lineAmountTotal": str(amount.quantize(Decimal("0.01"))),
        "firstShipmentAt": first.isoformat() if first is not None else None,
        "lastShipmentAt": last.isoformat() if last is not None else None,
    }


def verify_sales_file(
    path: Path, start_at: dt.datetime, end_at: dt.datetime, expected_rows: int,
) -> dict[str, Any]:
    """Check every row and reconcile its count with the same page query."""
    with Path(path).open("rb") as stream:
        if stream.read(8) != XLS_MAGIC:
            raise SalesValidationError("销售文件不是已核实的 XLS 格式")
    try:
        import xlrd
        book = xlrd.open_workbook(str(path), on_demand=True)
    except Exception as exc:
        raise SalesValidationError("无法读取 XLS 销售文件") from exc

    def rows() -> Iterable[dict[str, Any]]:
        found = False
        for sheet in book.sheets():
            if sheet.nrows == 0:
                continue
            headers = [str(v).strip() for v in sheet.row_values(0)]
            if any(headers.count(name) != 1 for name in REQUIRED_COLUMNS):
                raise SalesValidationError("销售文件缺少唯一的必需表头")
            found = True
            for index in range(1, sheet.nrows):
                values = sheet.row_values(index)
                if all(v == "" or v is None for v in values):
                    continue
                yield dict(zip(headers, values))
        if not found:
            raise SalesValidationError("销售文件没有有效工作表及表头")

    try:
        return _verify_rows(rows(), start_at, end_at, expected_rows)
    finally:
        book.release_resources()
