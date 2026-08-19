from __future__ import annotations

from dataclasses import dataclass
from typing import Any
import json
import math

LEARNING_MODEL_VERSION = 2
ROUTE_DISTANCE_TOLERANCE_MILES = 0.50
MIN_CUSTOMER_DISTANCE_MILES = 0.25
MIN_PLAUSIBLE_MPM = 0.60
MAX_PLAUSIBLE_MPM = 12.0
MIN_LEARNED_MPM = 0.90
MAX_LEARNED_MPM = 6.00


@dataclass(frozen=True)
class CustomerSample:
    offer_id: str
    distance: float
    actual_minutes: float
    google_mpm: float | None
    weight: float


@dataclass(frozen=True)
class RouteSample:
    offer_id: str
    predicted_minutes: float
    actual_minutes: float
    weight: float


def _copy(value: Any) -> Any:
    return json.loads(json.dumps(value))


def _finite(value: Any) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    number = float(value)
    return number if math.isfinite(number) else None


def _duration(durations: dict[str, Any], key: str, low: float, high: float) -> float | None:
    milliseconds = _finite(durations.get(key))
    if milliseconds is None:
        return None
    minutes = milliseconds / 60_000.0
    return minutes if low <= minutes <= high else None


def _clamp(value: float, low: float, high: float) -> float:
    return max(low, min(high, value))


def _round(value: float) -> float:
    return round(float(value), 4)


def _median(values: list[float]) -> float:
    ordered = sorted(values)
    middle = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[middle]
    return (ordered[middle - 1] + ordered[middle]) / 2.0


def _weighted_mean(values: list[float], weights: list[float]) -> float:
    total = sum(max(0.0, weight) for weight in weights)
    if total <= 1e-12:
        return sum(values) / len(values)
    return sum(value * max(0.0, weight) for value, weight in zip(values, weights)) / total


def _effective_n(weights: list[float]) -> float:
    positive = [max(0.0, weight) for weight in weights]
    total = sum(positive)
    squares = sum(weight * weight for weight in positive)
    return 0.0 if squares <= 1e-12 else total * total / squares


def _robust_scale(values: list[float]) -> float:
    if len(values) < 2:
        return 0.0
    center = _median(values)
    return 1.4826 * _median([abs(value - center) for value in values])


def _huber_weights(residuals: list[float]) -> list[float]:
    scale = _robust_scale(residuals)
    if scale <= 0.05:
        return [1.0] * len(residuals)
    limit = 1.5 * scale
    return [1.0 if abs(value) <= limit else limit / abs(value) for value in residuals]


def _robust_location(values: list[float], weights: list[float]) -> tuple[float, dict[str, float]]:
    center = _median(values)
    scale = _robust_scale(values)
    if scale <= 0.05:
        ordered = sorted(values)
        low_index = int(0.05 * (len(ordered) - 1))
        high_index = int(0.95 * (len(ordered) - 1))
        low = ordered[low_index]
        high = ordered[high_index]
    else:
        low, high = center - 3.5 * scale, center + 3.5 * scale
    clipped = [_clamp(value, low, high) for value in values]
    estimate = _weighted_mean(clipped, weights)
    for _ in range(4):
        robust = _huber_weights([value - estimate for value in clipped])
        estimate = _weighted_mean(clipped, [a * b for a, b in zip(weights, robust)])
    return estimate, {
        "median": center,
        "robust_scale": scale,
        "winsor_lower": low,
        "winsor_upper": high,
    }


def _weighted_quantile(values: list[float], weights: list[float], quantile: float) -> float:
    pairs = sorted((value, max(0.0, weight)) for value, weight in zip(values, weights))
    total = sum(weight for _, weight in pairs)
    if total <= 1e-12:
        return _median(values)
    target = _clamp(quantile, 0.0, 1.0) * total
    cumulative = 0.0
    for value, weight in pairs:
        cumulative += weight
        if cumulative >= target:
            return value
    return pairs[-1][0]


def _recency_weight(index: int, count: int, alpha: float) -> float:
    if count <= 1:
        return 1.0
    alpha = _clamp(alpha, 0.02, 0.35)
    half_life = max(10.0, math.log(0.5) / math.log(1.0 - alpha))
    age = count - 1 - index
    return math.exp(-math.log(2.0) * age / half_life)


def _confidence_weight(order: dict[str, Any]) -> float:
    confidence = str(order.get("offer", {}).get("restaurant_match_confidence", "")).upper()
    return {"HIGH": 1.0, "MEDIUM": 0.60, "LOW": 0.25}.get(confidence, 0.50)


def _route_age_weight(order: dict[str, Any]) -> float:
    offer = order.get("offer", {})
    route = order.get("route_snapshot", {})
    detected = _finite(offer.get("detected_at_wall_time_ms"))
    captured = _finite(route.get("captured_at_wall_time_ms"))
    if detected is None or captured is None:
        return 0.75
    age = captured - detected
    if age < -5_000:
        return 0.25
    if age <= 120_000:
        return 1.0
    if age <= 300_000:
        return 0.50
    return 0.20


def _valid_google_route(order: dict[str, Any]) -> bool:
    route = order.get("route_snapshot", {})
    status = str(route.get("route_status", "")).upper()
    source = str(route.get("route_source", "")).upper()
    return (not status or status == "SUCCESS") and (not source or "GOOGLE" in source)


def _band_spec(baseline: dict[str, Any]) -> tuple[list[float | None], list[float]]:
    bands = baseline["travel_model"]["fallback_minutes_per_mile_bands"]
    return (
        [None if band.get("through_miles") is None else float(band["through_miles"]) for band in bands],
        [float(band["minutes_per_mile"]) for band in bands],
    )


def _segments(distance: float, limits: list[float | None]) -> list[float]:
    result: list[float] = []
    previous = 0.0
    for limit in limits:
        upper = distance if limit is None else limit
        end = min(distance, upper)
        result.append(max(0.0, end - previous))
        previous = end
    return result


def _predict(distance: float, limits: list[float | None], rates: list[float]) -> float:
    return sum(segment * rate for segment, rate in zip(_segments(distance, limits), rates))


def _solve(matrix: list[list[float]], vector: list[float]) -> list[float]:
    size = len(vector)
    augmented = [list(row) + [value] for row, value in zip(matrix, vector)]
    for column in range(size):
        pivot = max(range(column, size), key=lambda row: abs(augmented[row][column]))
        augmented[column], augmented[pivot] = augmented[pivot], augmented[column]
        if abs(augmented[column][column]) <= 1e-12:
            augmented[column][column] = 1e-8
        divisor = augmented[column][column]
        augmented[column] = [value / divisor for value in augmented[column]]
        for row in range(size):
            if row == column:
                continue
            factor = augmented[row][column]
            augmented[row] = [left - factor * right for left, right in zip(augmented[row], augmented[column])]
    return [augmented[index][-1] for index in range(size)]


def _isotonic_decreasing(values: list[float], weights: list[float]) -> list[float]:
    blocks: list[dict[str, Any]] = []
    for index, (value, weight) in enumerate(zip(values, weights)):
        blocks.append({"start": index, "end": index, "value": value, "weight": max(weight, 1e-6)})
        while len(blocks) >= 2 and blocks[-2]["value"] < blocks[-1]["value"]:
            right = blocks.pop()
            left = blocks.pop()
            total = left["weight"] + right["weight"]
            blocks.append({
                "start": left["start"],
                "end": right["end"],
                "value": (left["value"] * left["weight"] + right["value"] * right["weight"]) / total,
                "weight": total,
            })
    result = [0.0] * len(values)
    for block in blocks:
        for index in range(block["start"], block["end"] + 1):
            result[index] = float(block["value"])
    return result


def _extract(orders: list[dict[str, Any]], alpha: float) -> dict[str, Any]:
    waits: list[float] = []
    wait_weights: list[float] = []
    wait_records: list[tuple[str, str | None, str | None, float, float]] = []
    dropoffs: list[float] = []
    dropoff_weights: list[float] = []
    customers: list[CustomerSample] = []
    routes: list[RouteSample] = []
    usable: set[str] = set()

    for index, order in enumerate(orders):
        offer_id = str(order.get("offer_id", ""))
        offer = order.get("offer", {})
        route = order.get("route_snapshot", {})
        durations = order.get("durations", {})
        recency = _recency_weight(index, len(orders), alpha)

        wait = _duration(durations, "restaurant_wait_ms", 0.0, 60.0)
        if wait is not None:
            waits.append(wait)
            wait_weights.append(recency)
            wait_records.append((offer_id, order.get("restaurant_place_id"), order.get("restaurant_name"), wait, recency))
            usable.add(offer_id)

        dropoff = _duration(durations, "dropoff_ms", 0.20, 20.0)
        if dropoff is not None:
            dropoffs.append(dropoff)
            dropoff_weights.append(recency)
            usable.add(offer_id)

        if not _valid_google_route(order):
            continue

        total = _finite(offer.get("displayed_total_miles"))
        route_distance = _finite(route.get("distance_miles"))
        eta = _finite(route.get("eta_minutes"))
        if total is None or route_distance is None or not (0.0 < total <= 100.0):
            continue
        if route_distance < 0.0 or route_distance > total + ROUTE_DISTANCE_TOLERANCE_MILES:
            continue

        confidence = _confidence_weight(order)
        age_weight = _route_age_weight(order)
        weight = recency * confidence * age_weight
        safe_route_distance = min(total, route_distance)
        customer_distance = max(0.0, total - safe_route_distance)

        google_mpm: float | None = None
        if eta is not None and 0.0 < eta <= 180.0 and route_distance >= 0.50:
            implied = eta / route_distance
            if 0.50 <= implied <= 12.0:
                google_mpm = implied

        actual_customer = _duration(durations, "drive_to_customer_ms", 0.30, 180.0)
        if actual_customer is not None and customer_distance >= MIN_CUSTOMER_DISTANCE_MILES:
            observed_mpm = actual_customer / customer_distance
            if MIN_PLAUSIBLE_MPM <= observed_mpm <= MAX_PLAUSIBLE_MPM:
                customers.append(CustomerSample(offer_id, customer_distance, actual_customer, google_mpm, weight))
                usable.add(offer_id)

        actual_restaurant = _duration(durations, "drive_to_restaurant_ms", 0.20, 120.0)
        if actual_restaurant is not None and eta is not None and 0.25 <= eta <= 120.0 and route_distance >= 0.25:
            routes.append(RouteSample(offer_id, eta, actual_restaurant, weight))
            usable.add(offer_id)

    return {
        "waits": waits,
        "wait_weights": wait_weights,
        "wait_records": wait_records,
        "dropoffs": dropoffs,
        "dropoff_weights": dropoff_weights,
        "customers": customers,
        "routes": routes,
        "usable": usable,
    }


def _shrunk_parameter(values: list[float], weights: list[float], baseline: float, minimum_samples: int, prior: float, low: float, high: float) -> tuple[float, dict[str, Any]]:
    if len(values) < minimum_samples:
        return baseline, {"enabled": False, "reason": "insufficient_samples", "samples": len(values), "active_value": baseline}
    raw, details = _robust_location(values, weights)
    effective = _effective_n(weights)
    evidence = effective / (effective + prior)
    active = _round(_clamp(baseline * (1.0 - evidence) + raw * evidence, low, high))
    return active, {
        "enabled": True,
        "samples": len(values),
        "effective_samples": _round(effective),
        "baseline_value": baseline,
        "raw_robust_estimate": _round(raw),
        "evidence_weight": _round(evidence),
        "active_value": active,
        **{key: _round(value) for key, value in details.items()},
    }


def _fit_customer_rates(samples: list[CustomerSample], limits: list[float | None], baseline_rates: list[float], minimum_samples: int, minimum_band_samples: int, prior: float) -> tuple[list[float], dict[str, Any]]:
    support = [0] * len(baseline_rates)
    for sample in samples:
        for index, segment in enumerate(_segments(sample.distance, limits)):
            if segment >= 0.10:
                support[index] += 1
    if len(samples) < minimum_samples:
        return list(baseline_rates), {"enabled": False, "reason": "insufficient_customer_samples", "samples": len(samples), "band_samples": support}

    rates = list(baseline_rates)
    robust = [1.0] * len(samples)
    size = len(rates)
    for _ in range(5):
        matrix = [[0.0] * size for _ in range(size)]
        vector = [0.0] * size
        for sample, robust_weight in zip(samples, robust):
            features = _segments(sample.distance, limits)
            weight = sample.weight * robust_weight
            for row in range(size):
                vector[row] += weight * features[row] * sample.actual_minutes
                for column in range(size):
                    matrix[row][column] += weight * features[row] * features[column]
        for index, baseline_rate in enumerate(baseline_rates):
            matrix[index][index] += prior
            vector[index] += prior * baseline_rate
        rates = [_clamp(value, MIN_LEARNED_MPM, MAX_LEARNED_MPM) for value in _solve(matrix, vector)]
        robust = _huber_weights([sample.actual_minutes - _predict(sample.distance, limits, rates) for sample in samples])

    gated: list[float] = []
    for index, fitted in enumerate(rates):
        if support[index] < minimum_band_samples:
            gated.append(baseline_rates[index])
        else:
            evidence = support[index] / (support[index] + prior)
            gated.append(baseline_rates[index] * (1.0 - evidence) + fitted * evidence)
    learned = [_round(_clamp(value, MIN_LEARNED_MPM, MAX_LEARNED_MPM)) for value in _isotonic_decreasing(gated, [count + prior for count in support])]
    baseline_mae = sum(abs(sample.actual_minutes - _predict(sample.distance, limits, baseline_rates)) for sample in samples) / len(samples)
    learned_mae = sum(abs(sample.actual_minutes - _predict(sample.distance, limits, learned)) for sample in samples) / len(samples)
    return learned, {
        "enabled": True,
        "samples": len(samples),
        "band_samples": support,
        "raw_fitted_rates": [_round(value) for value in rates],
        "baseline_mae_minutes": _round(baseline_mae),
        "learned_mae_minutes": _round(learned_mae),
    }


def _learn_google_bounds(samples: list[CustomerSample], baseline_min: float, baseline_max: float, minimum_samples: int, prior: float) -> tuple[float, float, dict[str, Any]]:
    usable = [sample for sample in samples if sample.google_mpm is not None]
    if len(usable) < minimum_samples:
        return baseline_min, baseline_max, {"enabled": False, "reason": "insufficient_google_rate_samples", "samples": len(usable)}
    values = [float(sample.google_mpm) for sample in usable]
    weights = [sample.weight for sample in usable]
    raw_min = _weighted_quantile(values, weights, 0.10)
    raw_max = _weighted_quantile(values, weights, 0.90)
    effective = _effective_n(weights)
    evidence = effective / (effective + prior)
    learned_min = _clamp(baseline_min * (1.0 - evidence) + raw_min * evidence, 0.75, 3.50)
    learned_max = _clamp(baseline_max * (1.0 - evidence) + raw_max * evidence, 2.00, 8.00)
    if learned_max < learned_min + 0.50:
        midpoint = (learned_min + learned_max) / 2.0
        learned_min, learned_max = midpoint - 0.25, midpoint + 0.25
    return _round(learned_min), _round(learned_max), {
        "enabled": True,
        "samples": len(usable),
        "effective_samples": _round(effective),
        "raw_p10": _round(raw_min),
        "raw_p90": _round(raw_max),
    }


def _selection_band(distance: float, limits: list[float | None]) -> int:
    for index, limit in enumerate(limits):
        if limit is None or distance <= limit:
            return index
    return len(limits) - 1


def _fit_google_weights(samples: list[CustomerSample], limits: list[float | None], local_rates: list[float], baseline_weights: list[float], google_min: float, google_max: float, minimum_per_band: int, prior: float) -> tuple[list[float], dict[str, Any]]:
    groups: list[list[CustomerSample]] = [[] for _ in baseline_weights]
    for sample in samples:
        if sample.google_mpm is not None:
            groups[_selection_band(sample.distance, limits)].append(sample)
    fitted: list[float] = []
    support: list[int] = []
    for index, group in enumerate(groups):
        support.append(len(group))
        baseline = baseline_weights[index]
        if len(group) < minimum_per_band:
            fitted.append(baseline)
            continue
        current = baseline
        for _ in range(4):
            residuals: list[float] = []
            rows: list[tuple[CustomerSample, float, float]] = []
            for sample in group:
                local = _predict(sample.distance, limits, local_rates)
                google = sample.distance * _clamp(float(sample.google_mpm), google_min, google_max)
                difference = google - local
                residuals.append(sample.actual_minutes - (local + current * difference))
                rows.append((sample, local, difference))
            robust = _huber_weights(residuals)
            numerator = prior * baseline
            denominator = prior
            for (sample, local, difference), robust_weight in zip(rows, robust):
                weight = sample.weight * robust_weight
                numerator += weight * difference * (sample.actual_minutes - local)
                denominator += weight * difference * difference
            current = baseline if denominator <= 1e-12 else _clamp(numerator / denominator, 0.0, 1.0)
        evidence = len(group) / (len(group) + prior)
        fitted.append(baseline * (1.0 - evidence) + current * evidence)
    result = [_round(_clamp(value, 0.0, 1.0)) for value in _isotonic_decreasing(fitted, [count + prior for count in support])]
    return result, {"band_samples": support, "minimum_samples_per_band": minimum_per_band}


def _route_mae(samples: list[RouteSample], multiplier: float, delay: float) -> float:
    total_weight = sum(sample.weight for sample in samples)
    if total_weight <= 1e-12:
        return float("inf")
    return sum(sample.weight * abs(sample.actual_minutes - (sample.predicted_minutes * multiplier + delay)) for sample in samples) / total_weight


def _fit_route(samples: list[RouteSample], allow_multiplier: bool) -> tuple[float, float]:
    if not allow_multiplier:
        errors = [sample.actual_minutes - sample.predicted_minutes for sample in samples]
        weights = [sample.weight for sample in samples]
        raw, _ = _robust_location(errors, weights)
        evidence = _effective_n(weights) / (_effective_n(weights) + 20.0)
        return 1.0, _clamp(raw * evidence, -2.0, 5.0)
    multiplier, delay = 1.0, 0.0
    robust = [1.0] * len(samples)
    for _ in range(5):
        a00, a01, a11 = 60.0, 0.0, 20.0
        b0, b1 = 60.0, 0.0
        for sample, robust_weight in zip(samples, robust):
            weight = sample.weight * robust_weight
            x, y = sample.predicted_minutes, sample.actual_minutes
            a00 += weight * x * x
            a01 += weight * x
            a11 += weight
            b0 += weight * x * y
            b1 += weight * y
        multiplier, delay = _solve([[a00, a01], [a01, a11]], [b0, b1])
        multiplier = _clamp(multiplier, 0.75, 1.25)
        delay = _clamp(delay, -2.0, 5.0)
        robust = _huber_weights([sample.actual_minutes - (sample.predicted_minutes * multiplier + delay) for sample in samples])
    return multiplier, delay


def _learn_route(samples: list[RouteSample], minimum_samples: int) -> tuple[float, float, bool, dict[str, Any]]:
    if len(samples) < minimum_samples:
        return 1.0, 0.0, False, {"enabled": False, "reason": "insufficient_route_samples", "samples": len(samples)}
    validation_count = max(3, int(round(len(samples) * 0.20)))
    training, validation = samples[:-validation_count], samples[-validation_count:]
    if len(training) < 8:
        return 1.0, 0.0, False, {"enabled": False, "reason": "insufficient_route_training_split", "samples": len(samples)}
    spread = max(sample.predicted_minutes for sample in training) - min(sample.predicted_minutes for sample in training)
    allow_multiplier = len(training) >= 30 and spread >= 5.0
    trial_multiplier, trial_delay = _fit_route(training, allow_multiplier)
    raw_mae = _route_mae(validation, 1.0, 0.0)
    corrected_mae = _route_mae(validation, trial_multiplier, trial_delay)
    required = max(0.25, raw_mae * 0.05)
    if not (math.isfinite(raw_mae) and math.isfinite(corrected_mae) and raw_mae - corrected_mae >= required):
        return 1.0, 0.0, False, {
            "enabled": False,
            "reason": "validation_did_not_improve",
            "samples": len(samples),
            "validation_samples": len(validation),
            "raw_validation_mae_minutes": _round(raw_mae),
            "corrected_validation_mae_minutes": _round(corrected_mae),
            "trial_multiplier": _round(trial_multiplier),
            "trial_fixed_delay_minutes": _round(trial_delay),
        }
    multiplier, delay = _fit_route(samples, allow_multiplier)
    return _round(multiplier), _round(delay), True, {
        "enabled": True,
        "samples": len(samples),
        "validation_samples": len(validation),
        "model": "multiplier_plus_delay" if allow_multiplier else "additive_delay_only",
        "raw_validation_mae_minutes": _round(raw_mae),
        "corrected_validation_mae_minutes": _round(corrected_mae),
    }


def _restaurant_waits(records: list[tuple[str, str | None, str | None, float, float]], global_wait: float, minimum_samples: int, prior: float) -> dict[str, dict[str, Any]]:
    grouped: dict[str, dict[str, Any]] = {}
    for _, place_id, name, value, weight in records:
        key = str(place_id) if place_id else "name:" + str(name or "unknown").strip().lower()
        group = grouped.setdefault(key, {"restaurant_place_id": place_id, "restaurant_name": name, "values": [], "weights": []})
        group["values"].append(value)
        group["weights"].append(weight)
    result: dict[str, dict[str, Any]] = {}
    for key, group in grouped.items():
        if len(group["values"]) < minimum_samples:
            continue
        raw, _ = _robust_location(group["values"], group["weights"])
        effective = _effective_n(group["weights"])
        evidence = effective / (effective + prior)
        blended = _round(_clamp(global_wait * (1.0 - evidence) + raw * evidence, 0.0, 60.0))
        result[key] = {
            "restaurant_place_id": group["restaurant_place_id"],
            "restaurant_name": group["restaurant_name"],
            "samples": len(group["values"]),
            "effective_samples": _round(effective),
            "specific_robust_minutes": _round(raw),
            "specific_weight": _round(evidence),
            "blended_wait_minutes": blended,
        }
    return result


def build_improved_engine_candidate(*, orders: list[dict[str, Any]], baseline_parameters: dict[str, Any], previous_config: dict[str, Any] | None, minimum_global_samples: int, minimum_restaurant_samples: int, ewma_alpha: float, restaurant_prior_strength: float) -> dict[str, Any]:
    minimum_global_samples = max(10, int(minimum_global_samples))
    minimum_restaurant_samples = max(3, int(minimum_restaurant_samples))
    minimum_customer_samples = max(10, minimum_global_samples)
    minimum_customer_band_samples = max(5, minimum_global_samples // 2)
    minimum_google_samples = max(15, minimum_global_samples)
    minimum_google_band_samples = max(8, minimum_global_samples // 2)
    minimum_route_samples = max(15, minimum_global_samples)

    extracted = _extract(orders, ewma_alpha)
    baseline_global = baseline_parameters["global"]
    baseline_travel = baseline_parameters["travel_model"]

    restaurant_wait, wait_diag = _shrunk_parameter(
        extracted["waits"], extracted["wait_weights"], float(baseline_global["restaurant_wait_minutes"]), minimum_global_samples, 15.0, 0.0, 30.0
    )
    dropoff, dropoff_diag = _shrunk_parameter(
        extracted["dropoffs"], extracted["dropoff_weights"], float(baseline_global["customer_dropoff_minutes"]), minimum_global_samples, 15.0, 0.50, 12.0
    )

    limits, baseline_rates = _band_spec(baseline_parameters)
    customer_rates, customer_diag = _fit_customer_rates(
        extracted["customers"], limits, baseline_rates, minimum_customer_samples, minimum_customer_band_samples, 18.0
    )
    google_min, google_max, google_bounds_diag = _learn_google_bounds(
        extracted["customers"], float(baseline_travel["google_minutes_per_mile_minimum"]), float(baseline_travel["google_minutes_per_mile_maximum"]), minimum_google_samples, 30.0
    )
    baseline_google_weights = [float(item["google_weight"]) for item in baseline_travel["customer_google_weight_bands"]]
    google_weights, google_weight_diag = _fit_google_weights(
        extracted["customers"], limits, customer_rates, baseline_google_weights, google_min, google_max, minimum_google_band_samples, 18.0
    )
    route_multiplier, route_delay, route_enabled, route_diag = _learn_route(extracted["routes"], minimum_route_samples)

    waits = _restaurant_waits(
        extracted["wait_records"], restaurant_wait, minimum_restaurant_samples, max(10.0, restaurant_prior_strength)
    )

    previous_global = previous_config.get("global", {}) if isinstance(previous_config, dict) else {}
    global_parameters = _copy(baseline_parameters["global"])
    for field in ("next_offer_wait_minutes", "repositioning_minutes", "repositioning_miles"):
        value = _finite(previous_global.get(field))
        if value is not None:
            global_parameters[field] = value
    global_parameters.update({
        "route_multiplier": route_multiplier,
        "route_fixed_delay_minutes": route_delay,
        "restaurant_wait_minutes": restaurant_wait,
        "customer_dropoff_minutes": dropoff,
    })

    travel_model = _copy(baseline_travel)
    travel_model["fallback_minutes_per_mile_bands"] = [
        {"through_miles": limit, "minutes_per_mile": rate}
        for limit, rate in zip(limits, customer_rates)
    ]
    travel_model["google_minutes_per_mile_minimum"] = google_min
    travel_model["google_minutes_per_mile_maximum"] = google_max
    travel_model["customer_google_weight_bands"] = [
        {"through_miles": limit, "google_weight": weight}
        for limit, weight in zip(limits, google_weights)
    ]

    scoring_source = previous_config.get("scoring") if isinstance(previous_config, dict) else None
    scoring = _copy(scoring_source if isinstance(scoring_source, dict) else baseline_parameters["scoring"])
    features = _copy(baseline_parameters["features"])
    features["use_server_route_correction"] = route_enabled
    features["use_restaurant_specific_wait"] = True
    previous_features = previous_config.get("features", {}) if isinstance(previous_config, dict) else {}
    for field in ("include_next_offer_wait_in_score", "include_repositioning_in_score"):
        if field in previous_features:
            features[field] = bool(previous_features[field])

    source = {
        "eligible_orders": len(orders),
        "usable_orders": len(extracted["usable"]),
        "route_samples": len(extracted["routes"]),
        "restaurant_wait_samples": len(extracted["waits"]),
        "dropoff_samples": len(extracted["dropoffs"]),
        "customer_drive_samples": len(extracted["customers"]),
        "customer_google_samples": sum(1 for sample in extracted["customers"] if sample.google_mpm is not None),
        "customer_band_samples": customer_diag.get("band_samples", [0] * len(limits)),
    }

    changed = any([
        restaurant_wait != float(baseline_global["restaurant_wait_minutes"]),
        dropoff != float(baseline_global["customer_dropoff_minutes"]),
        customer_rates != [_round(value) for value in baseline_rates],
        google_min != float(baseline_travel["google_minutes_per_mile_minimum"]),
        google_max != float(baseline_travel["google_minutes_per_mile_maximum"]),
        google_weights != [_round(value) for value in baseline_google_weights],
        route_enabled,
        bool(waits),
    ])

    return {
        "status": "learned" if changed else "stabilizing",
        "source": source,
        "global": global_parameters,
        "restaurant_waits": waits,
        "scoring": scoring,
        "travel_model": travel_model,
        "features": features,
        "learning": {
            "model_version": LEARNING_MODEL_VERSION,
            "ewma_alpha": ewma_alpha,
            "minimum_global_samples": minimum_global_samples,
            "minimum_restaurant_samples": minimum_restaurant_samples,
            "minimum_customer_samples": minimum_customer_samples,
            "minimum_customer_band_samples": minimum_customer_band_samples,
            "minimum_google_samples": minimum_google_samples,
            "minimum_google_band_samples": minimum_google_band_samples,
            "minimum_route_samples": minimum_route_samples,
            "global_prior_strength": 15.0,
            "customer_rate_prior_strength": 18.0,
            "google_bound_prior_strength": 30.0,
            "google_weight_prior_strength": 18.0,
            "restaurant_prior_strength": max(10.0, restaurant_prior_strength),
            "diagnostics": {
                "restaurant_wait": wait_diag,
                "customer_dropoff": dropoff_diag,
                "customer_travel_rates": customer_diag,
                "google_rate_bounds": google_bounds_diag,
                "google_blending_weights": google_weight_diag,
                "restaurant_route_correction": route_diag,
            },
            "notes": [
                "Scoring weights and thresholds remain policy choices until driver feedback labels exist.",
                "Customer travel rates use robust regularized monotonic piecewise fitting.",
                "Route correction is enabled only when chronological validation improves Google ETA error.",
                "All learned timing parameters shrink toward baseline while data is sparse.",
            ],
        },
    }
