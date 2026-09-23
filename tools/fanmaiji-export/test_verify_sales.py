"""Check data-loss and reporting-date failures without real customer data."""
import datetime as dt
import unittest
from zoneinfo import ZoneInfo

from verify_sales import SalesValidationError, _verify_rows


class SalesVerificationTests(unittest.TestCase):
    def setUp(self):
        zone = ZoneInfo("Asia/Shanghai")
        self.start = dt.datetime(2026, 9, 22, tzinfo=zone)
        self.end = dt.datetime(2026, 9, 22, 23, 59, 59, tzinfo=zone)

    def row(self, when="2026-09-22 10:00:00", quantity=1, amount=4.3, order="example"):
        return {"商品名称": "示例商品", "设备ID": "device-example", "出货时间": when,
                "出货数量": quantity, "商品金额(元)": amount, "订单号": order}

    def verify(self, rows, expected):
        return _verify_rows(rows, self.start, self.end, expected)

    def test_multiple_items_in_one_order_are_preserved(self):
        stats = self.verify([self.row(quantity=3, amount=12.9), self.row(amount=4.3)], 2)
        self.assertEqual((stats["rows"], stats["orders"], stats["quantity"], stats["lineAmountTotal"]),
                         (2, 1, 4, "17.20"))

    def test_out_of_range_middle_row_is_rejected(self):
        with self.assertRaisesRegex(SalesValidationError, "日期范围"):
            self.verify([self.row(), self.row("2026-09-23 00:00:00"), self.row()], 3)

    def test_partial_download_is_rejected(self):
        with self.assertRaisesRegex(SalesValidationError, "不一致"):
            self.verify([self.row()], 338)

    def test_nonfinite_amount_is_rejected(self):
        with self.assertRaises(SalesValidationError):
            self.verify([self.row(amount="NaN")], 1)

    def test_missing_or_numeric_identifiers_are_rejected(self):
        for field in ("商品名称", "设备ID", "订单号"):
            for invalid in ("", None, 123):
                with self.subTest(field=field, invalid=invalid):
                    row = self.row()
                    row[field] = invalid
                    with self.assertRaises(SalesValidationError):
                        self.verify([row], 1)

    def test_empty_day_requires_zero_on_page(self):
        self.assertEqual(self.verify([], 0)["rows"], 0)
        with self.assertRaises(SalesValidationError):
            self.verify([], 1)


if __name__ == "__main__":
    unittest.main()
