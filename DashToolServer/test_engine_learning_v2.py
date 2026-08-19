from __future__ import annotations

import unittest

from engine_learning_v2 import build_improved_engine_candidate

BASELINE = {
    "global": {
        "route_multiplier": 1.0,
        "route_fixed_delay_minutes": 0.0,
        "restaurant_wait_minutes": 4.0,
        "customer_dropoff_minutes": 2.5,
        "next_offer_wait_minutes": 0.0,
        "repositioning_minutes": 0.0,
        "repositioning_miles": 0.0,
    },
    "scoring": {
        "hourly_rate_weight": 0.60,
        "dollars_per_mile_weight": 0.25,
        "net_profit_weight": 0.15,
        "hourly_rate_thresholds": {"poor": 10.0, "middle": 20.0, "excellent": 30.0},
        "dollars_per_mile_thresholds": {"poor": 0.75, "middle": 1.50, "excellent": 2.25},
        "net_profit_thresholds": {"poor": 2.0, "middle": 6.0, "excellent": 10.0},
    },
    "travel_model": {
        "fallback_minutes_per_mile_bands": [
            {"through_miles": 2.0, "minutes_per_mile": 3.0},
            {"through_miles": 5.0, "minutes_per_mile": 2.6},
            {"through_miles": 8.0, "minutes_per_mile": 2.2},
            {"through_miles": 12.0, "minutes_per_mile": 1.8},
            {"through_miles": None, "minutes_per_mile": 1.6},
        ],
        "google_minutes_per_mile_minimum": 1.5,
        "google_minutes_per_mile_maximum": 4.0,
        "customer_google_weight_bands": [
            {"through_miles": 2.0, "google_weight": 0.75},
            {"through_miles": 5.0, "google_weight": 0.65},
            {"through_miles": 8.0, "google_weight": 0.50},
            {"through_miles": 12.0, "google_weight": 0.35},
            {"through_miles": None, "google_weight": 0.25},
        ],
    },
    "features": {
        "use_server_route_correction": True,
        "use_restaurant_specific_wait": True,
        "include_next_offer_wait_in_score": False,
        "include_repositioning_in_score": False,
    },
}


def make_order(index: int, total: float, restaurant_distance: float, customer_minutes: float, restaurant_minutes: float = 5.0, eta: float = 4.5, wait: float = 3.0, dropoff: float = 2.5) -> dict:
    return {
        "offer_id": f"offer_{index}",
        "received_at": f"2026-07-28T12:{index % 60:02d}:00+00:00",
        "restaurant_place_id": "place",
        "restaurant_name": "Restaurant",
        "offer": {
            "displayed_total_miles": total,
            "restaurant_match_confidence": "HIGH",
            "detected_at_wall_time_ms": 1_000_000 + index * 60_000,
        },
        "route_snapshot": {
            "route_status": "SUCCESS",
            "route_source": "GOOGLE_TRAFFIC_AWARE",
            "distance_miles": restaurant_distance,
            "eta_minutes": eta,
            "captured_at_wall_time_ms": 1_000_500 + index * 60_000,
        },
        "durations": {
            "drive_to_restaurant_ms": restaurant_minutes * 60_000,
            "restaurant_wait_ms": wait * 60_000,
            "drive_to_customer_ms": customer_minutes * 60_000,
            "dropoff_ms": dropoff * 60_000,
        },
    }


class Tests(unittest.TestCase):
    def candidate(self, orders: list[dict]) -> dict:
        return build_improved_engine_candidate(
            orders=orders,
            baseline_parameters=BASELINE,
            previous_config={
                "scoring": BASELINE["scoring"],
                "global": BASELINE["global"],
                "features": BASELINE["features"],
            },
            minimum_global_samples=10,
            minimum_restaurant_samples=5,
            ewma_alpha=0.20,
            restaurant_prior_strength=15.0,
        )

    def test_small_sample_does_not_overfit(self) -> None:
        result = self.candidate([
            make_order(index, 4.0, 1.0, 7.0)
            for index in range(3)
        ])
        self.assertEqual(result["global"]["restaurant_wait_minutes"], 4.0)
        self.assertFalse(result["features"]["use_server_route_correction"])
        self.assertEqual(
            [band["minutes_per_mile"] for band in result["travel_model"]["fallback_minutes_per_mile_bands"]],
            [3.0, 2.6, 2.2, 1.8, 1.6],
        )

    def test_customer_rates_learn_monotonically(self) -> None:
        orders: list[dict] = []
        for index in range(50):
            distance = 1.0 + index % 18
            if distance <= 2:
                minutes = distance * 2.6
            elif distance <= 5:
                minutes = 5.2 + (distance - 2) * 2.2
            elif distance <= 8:
                minutes = 11.8 + (distance - 5) * 1.9
            elif distance <= 12:
                minutes = 17.5 + (distance - 8) * 1.6
            else:
                minutes = 23.9 + (distance - 12) * 1.4
            orders.append(make_order(index, distance + 1.0, 1.0, minutes, restaurant_minutes=2.8, eta=2.5))
        result = self.candidate(orders)
        rates = [band["minutes_per_mile"] for band in result["travel_model"]["fallback_minutes_per_mile_bands"]]
        self.assertTrue(all(left >= right for left, right in zip(rates, rates[1:])))
        self.assertNotEqual(rates, [3.0, 2.6, 2.2, 1.8, 1.6])

    def test_wait_outlier_is_resisted(self) -> None:
        orders = [make_order(index, 4.0, 1.0, 8.0, wait=3.0) for index in range(20)]
        orders[-1]["durations"]["restaurant_wait_ms"] = 60 * 60_000
        result = self.candidate(orders)
        self.assertLess(result["global"]["restaurant_wait_minutes"], 6.0)

    def test_required_android_schema_remains(self) -> None:
        result = self.candidate([])
        for key in ("global", "scoring", "travel_model", "features", "restaurant_waits"):
            self.assertIn(key, result)


if __name__ == "__main__":
    unittest.main()
