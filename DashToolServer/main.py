from __future__ import annotations

from contextlib import asynccontextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import json
import math
import sqlite3
import statistics

from fastapi import FastAPI, HTTPException, Query
from pydantic import BaseModel


BASE_DIR = Path(__file__).resolve().parent
DATA_DIR = BASE_DIR / "data"
DATABASE_PATH = DATA_DIR / "dashtool.db"
RESTAURANT_CENTER_LINK_RADIUS_METERS = 1_609.344


BASELINE_ENGINE_PARAMETERS: dict[str, Any] = {
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
        "hourly_rate_thresholds": {
            "poor": 10.0,
            "middle": 20.0,
            "excellent": 30.0,
        },
        "dollars_per_mile_thresholds": {
            "poor": 0.75,
            "middle": 1.50,
            "excellent": 2.25,
        },
        "net_profit_thresholds": {
            "poor": 2.0,
            "middle": 6.0,
            "excellent": 10.0,
        },
    },
    "travel_model": {
        "fallback_minutes_per_mile_bands": [
            {
                "through_miles": 2.0,
                "minutes_per_mile": 3.0,
            },
            {
                "through_miles": 5.0,
                "minutes_per_mile": 2.6,
            },
            {
                "through_miles": 8.0,
                "minutes_per_mile": 2.2,
            },
            {
                "through_miles": 12.0,
                "minutes_per_mile": 1.8,
            },
            {
                "through_miles": None,
                "minutes_per_mile": 1.6,
            },
        ],
        "google_minutes_per_mile_minimum": 1.5,
        "google_minutes_per_mile_maximum": 4.0,
        "customer_google_weight_bands": [
            {
                "through_miles": 2.0,
                "google_weight": 0.75,
            },
            {
                "through_miles": 5.0,
                "google_weight": 0.65,
            },
            {
                "through_miles": 8.0,
                "google_weight": 0.50,
            },
            {
                "through_miles": 12.0,
                "google_weight": 0.35,
            },
            {
                "through_miles": None,
                "google_weight": 0.25,
            },
        ],
    },
    "features": {
        "use_server_route_correction": True,
        "use_restaurant_specific_wait": True,
        "include_next_offer_wait_in_score": False,
        "include_repositioning_in_score": False,
    },
}


class TrainingStatusUpdate(BaseModel):
    exclude_from_training: bool
    quality_note: str | None = None


def get_connection() -> sqlite3.Connection:
    """Open a SQLite connection configured for this small server."""
    connection = sqlite3.connect(
        DATABASE_PATH,
        timeout=10,
    )
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA journal_mode=WAL")
    return connection


def ensure_column(
    connection: sqlite3.Connection,
    table_name: str,
    column_name: str,
    definition: str,
) -> None:
    """Add a column when upgrading an existing local database."""
    columns = {
        row["name"]
        for row in connection.execute(
            f"PRAGMA table_info({table_name})"
        ).fetchall()
    }

    if column_name not in columns:
        connection.execute(
            f"""
            ALTER TABLE {table_name}
            ADD COLUMN {column_name} {definition}
            """
        )


def synchronize_training_fields(
    connection: sqlite3.Connection,
) -> None:
    """Copy training flags from stored raw JSON into searchable columns."""
    rows = connection.execute(
        """
        SELECT
            offer_id,
            raw_json
        FROM orders
        """
    ).fetchall()

    for row in rows:
        try:
            payload = json.loads(
                row["raw_json"]
            )
        except json.JSONDecodeError:
            continue

        offer = payload.get("offer")

        if not isinstance(offer, dict):
            continue

        excluded = bool(
            offer.get(
                "exclude_from_training",
                False,
            )
        )

        quality_note = offer.get(
            "quality_note"
        )

        if (
            quality_note is not None
            and not isinstance(
                quality_note,
                str,
            )
        ):
            quality_note = str(
                quality_note
            )

        connection.execute(
            """
            UPDATE orders
            SET
                exclude_from_training = ?,
                quality_note = ?
            WHERE offer_id = ?
            """,
            (
                int(excluded),
                quality_note,
                row["offer_id"],
            ),
        )


def initialize_database() -> None:
    """Create or upgrade the local DashTool database."""
    DATA_DIR.mkdir(
        parents=True,
        exist_ok=True,
    )

    with get_connection() as connection:
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS orders (
                offer_id TEXT PRIMARY KEY,
                received_at TEXT NOT NULL,
                schema_version INTEGER,
                restaurant_place_id TEXT,
                restaurant_name TEXT,
                offered_payout REAL,
                displayed_total_miles REAL,
                engine_version INTEGER,
                exclude_from_training INTEGER NOT NULL DEFAULT 0,
                quality_note TEXT,
                raw_json TEXT NOT NULL
            )
            """
        )

        ensure_column(
            connection=connection,
            table_name="orders",
            column_name="exclude_from_training",
            definition="INTEGER NOT NULL DEFAULT 0",
        )

        ensure_column(
            connection=connection,
            table_name="orders",
            column_name="quality_note",
            definition="TEXT",
        )

        synchronize_training_fields(
            connection
        )

        # Waiting-area data is intentionally kept separate from completed
        # order training data. This avoids changing the existing order schema
        # and lets us validate the new pipeline independently.
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS restaurant_observations (
                observation_id TEXT PRIMARY KEY,
                offer_id TEXT,
                restaurant_key TEXT NOT NULL,
                restaurant_place_id TEXT,
                restaurant_name TEXT NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                accuracy_meters REAL,
                observed_at_wall_time_ms INTEGER NOT NULL,
                coordinate_source TEXT NOT NULL,
                received_at TEXT NOT NULL,
                raw_json TEXT NOT NULL
            )
            """
        )

        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS restaurants (
                restaurant_key TEXT PRIMARY KEY,
                restaurant_place_id TEXT,
                restaurant_name TEXT NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                observation_count INTEGER NOT NULL DEFAULT 1,
                first_observed_at_wall_time_ms INTEGER NOT NULL,
                last_observed_at_wall_time_ms INTEGER NOT NULL,
                last_accuracy_meters REAL,
                coordinate_source TEXT NOT NULL
            )
            """
        )

        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS waiting_centers (
                center_id TEXT PRIMARY KEY,
                center_name TEXT NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                source TEXT NOT NULL DEFAULT 'UNKNOWN',
                last_seen_at_wall_time_ms INTEGER,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """
        )

        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS waiting_center_restaurants (
                center_id TEXT NOT NULL,
                restaurant_key TEXT NOT NULL,
                distance_meters REAL,
                PRIMARY KEY (center_id, restaurant_key),
                FOREIGN KEY (center_id) REFERENCES waiting_centers(center_id),
                FOREIGN KEY (restaurant_key) REFERENCES restaurants(restaurant_key)
            )
            """
        )

        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS waiting_sessions (
                waiting_session_id TEXT PRIMARY KEY,
                previous_offer_id TEXT,
                next_offer_id TEXT,
                delivery_completed_at_wall_time_ms INTEGER,
                center_id TEXT,
                center_name TEXT,
                center_latitude REAL,
                center_longitude REAL,
                recommended_center_id TEXT,
                center_arrived_at_wall_time_ms INTEGER,
                arrival_latitude REAL,
                arrival_longitude REAL,
                next_offer_detected_at_wall_time_ms INTEGER NOT NULL,
                travel_to_center_ms INTEGER,
                wait_at_center_ms INTEGER,
                offer_before_arrival INTEGER NOT NULL DEFAULT 0,
                exclude_from_learning INTEGER NOT NULL DEFAULT 0,
                exclusion_reason TEXT,
                received_at TEXT NOT NULL,
                raw_json TEXT NOT NULL
            )
            """
        )

        connection.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_waiting_sessions_center
            ON waiting_sessions(center_id)
            """
        )

        connection.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_restaurant_observations_key
            ON restaurant_observations(restaurant_key)
            """
        )

        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS offer_wait_samples (
                sample_id TEXT PRIMARY KEY,
                next_offer_id TEXT,
                range_min_minutes REAL NOT NULL,
                range_max_minutes REAL NOT NULL,
                range_midpoint_minutes REAL NOT NULL,
                range_observed_at_wall_time_ms INTEGER,
                wait_started_at_wall_time_ms INTEGER NOT NULL,
                next_offer_detected_at_wall_time_ms INTEGER NOT NULL,
                actual_wait_ms INTEGER NOT NULL,
                actual_wait_minutes REAL NOT NULL,
                prediction_error_minutes REAL NOT NULL,
                start_reason TEXT,
                next_offer_detection_source TEXT,
                local_hour INTEGER,
                day_of_week INTEGER,
                exclude_from_learning INTEGER NOT NULL DEFAULT 0,
                exclusion_reason TEXT,
                received_at TEXT NOT NULL,
                raw_json TEXT NOT NULL
            )
            """
        )

        connection.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_offer_wait_range
            ON offer_wait_samples(
                range_min_minutes,
                range_max_minutes,
                exclude_from_learning
            )
            """
        )

        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS customer_map_samples (
                sample_id TEXT PRIMARY KEY,
                offer_id TEXT,
                prediction_id TEXT,
                restaurant_place_id TEXT,
                predicted_at_wall_time_ms INTEGER,
                screenshot_captured_at_wall_time_ms INTEGER,
                raw_latitude REAL NOT NULL,
                raw_longitude REAL NOT NULL,
                corrected_latitude_at_prediction REAL,
                corrected_longitude_at_prediction REAL,
                calibration_sample_count_at_prediction INTEGER,
                calibration_confidence_at_prediction REAL,
                calibration_model_type_at_prediction TEXT,
                actual_latitude REAL NOT NULL,
                actual_longitude REAL NOT NULL,
                actual_accuracy_meters REAL,
                actual_location_wall_time_ms INTEGER,
                actual_requested_at_wall_time_ms INTEGER,
                actual_location_source TEXT,
                delivery_confirmed_at_wall_time_ms INTEGER,
                confirmation_source TEXT,
                driver_latitude REAL NOT NULL,
                driver_longitude REAL NOT NULL,
                driver_accuracy_meters REAL,
                driver_location_wall_time_ms INTEGER,
                driver_anchor_age_ms INTEGER,
                restaurant_latitude REAL NOT NULL,
                restaurant_longitude REAL NOT NULL,
                driver_pixel_x INTEGER,
                driver_pixel_y INTEGER,
                restaurant_pixel_x INTEGER,
                restaurant_pixel_y INTEGER,
                customer_pixel_x INTEGER,
                customer_pixel_y INTEGER,
                restaurant_white_density REAL,
                restaurant_house_score REAL,
                customer_white_density REAL,
                customer_house_score REAL,
                calibration_pixel_distance REAL,
                customer_pixel_distance REAL,
                extrapolation_ratio REAL,
                anchor_straight_line_meters REAL,
                approximate_meters_per_pixel REAL,
                raw_error_east_meters REAL NOT NULL,
                raw_error_north_meters REAL NOT NULL,
                raw_error_meters REAL NOT NULL,
                exclude_from_learning INTEGER NOT NULL DEFAULT 0,
                exclusion_reason TEXT,
                received_at TEXT NOT NULL,
                raw_json TEXT NOT NULL
            )
            """
        )

        connection.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_customer_map_learning
            ON customer_map_samples(
                exclude_from_learning,
                restaurant_place_id
            )
            """
        )

        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS engine_configs (
                engine_version INTEGER PRIMARY KEY,
                generated_at TEXT NOT NULL,
                source_eligible_orders INTEGER NOT NULL,
                source_usable_orders INTEGER NOT NULL,
                config_json TEXT NOT NULL
            )
            """
        )

        existing_config = connection.execute(
            """
            SELECT
                engine_version
            FROM engine_configs
            ORDER BY engine_version DESC
            LIMIT 1
            """
        ).fetchone()

        if existing_config is None:
            generated_at = datetime.now(
                timezone.utc
            ).isoformat()

            baseline_config = {
                "engine_version": 1,
                "generated_at": generated_at,
                "status": "baseline",
                "source": {
                    "eligible_orders": 0,
                    "usable_orders": 0,
                },
                **json.loads(
                    json.dumps(
                        BASELINE_ENGINE_PARAMETERS
                    )
                ),
                "restaurant_waits": {},
                "learning": {
                    "ewma_alpha": 0.20,
                    "minimum_global_samples": 5,
                    "minimum_restaurant_samples": 2,
                    "restaurant_prior_strength": 10.0,
                },
            }

            connection.execute(
                """
                INSERT INTO engine_configs (
                    engine_version,
                    generated_at,
                    source_eligible_orders,
                    source_usable_orders,
                    config_json
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                (
                    1,
                    generated_at,
                    0,
                    0,
                    json.dumps(
                        baseline_config,
                        ensure_ascii=False,
                        separators=(",", ":"),
                    ),
                ),
            )

        connection.commit()


@asynccontextmanager
async def lifespan(_: FastAPI):
    initialize_database()
    yield


app = FastAPI(
    title="DashTool Server",
    version="0.9.0",
    lifespan=lifespan,
)


@app.get("/health")
def health() -> dict[str, str]:
    """Confirm that the server is running."""
    return {"status": "ok"}


@app.post("/orders")
def receive_order(
    payload: dict[str, Any],
) -> dict[str, str]:
    """Store one completed DashTool order."""
    offer = payload.get("offer")

    if not isinstance(offer, dict):
        raise HTTPException(
            status_code=422,
            detail="The JSON must contain an offer object.",
        )

    offer_id = offer.get("offer_id")

    if (
        not isinstance(
            offer_id,
            str,
        )
        or not offer_id.strip()
    ):
        raise HTTPException(
            status_code=422,
            detail="The offer must contain a valid offer_id.",
        )

    excluded = bool(
        offer.get(
            "exclude_from_training",
            False,
        )
    )

    quality_note = offer.get(
        "quality_note"
    )

    if (
        quality_note is not None
        and not isinstance(
            quality_note,
            str,
        )
    ):
        quality_note = str(
            quality_note
        )

    raw_json = json.dumps(
        payload,
        ensure_ascii=False,
        separators=(",", ":"),
    )

    try:
        with get_connection() as connection:
            connection.execute(
                """
                INSERT INTO orders (
                    offer_id,
                    received_at,
                    schema_version,
                    restaurant_place_id,
                    restaurant_name,
                    offered_payout,
                    displayed_total_miles,
                    engine_version,
                    exclude_from_training,
                    quality_note,
                    raw_json
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    offer_id,
                    datetime.now(
                        timezone.utc
                    ).isoformat(),
                    payload.get(
                        "schema_version"
                    ),
                    offer.get(
                        "restaurant_place_id"
                    ),
                    offer.get(
                        "restaurant_name"
                    ),
                    offer.get(
                        "offered_payout"
                    ),
                    offer.get(
                        "displayed_total_miles"
                    ),
                    offer.get(
                        "engine_version"
                    ),
                    int(
                        excluded
                    ),
                    quality_note,
                    raw_json,
                ),
            )
            connection.commit()
    except sqlite3.IntegrityError:
        return {
            "status": "already_exists",
            "offer_id": offer_id,
        }
    except sqlite3.Error as exc:
        raise HTTPException(
            status_code=500,
            detail=f"Database error: {exc}",
        ) from exc

    return {
        "status": "stored",
        "offer_id": offer_id,
    }


def _normalized_restaurant_key(
    place_id: Any,
    restaurant_name: Any,
) -> str:
    if isinstance(place_id, str) and place_id.strip():
        return "place:" + place_id.strip()

    if not isinstance(restaurant_name, str) or not restaurant_name.strip():
        raise HTTPException(
            status_code=422,
            detail="A restaurant name or place ID is required.",
        )

    return "name:" + " ".join(
        restaurant_name.strip().lower().split()
    )


def _require_coordinate(
    value: Any,
    name: str,
    minimum: float,
    maximum: float,
) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError):
        raise HTTPException(
            status_code=422,
            detail=f"{name} must be numeric.",
        )

    if number < minimum or number > maximum:
        raise HTTPException(
            status_code=422,
            detail=f"{name} is outside its valid range.",
        )

    return number


def _distance_meters(
    lat1: float,
    lon1: float,
    lat2: float,
    lon2: float,
) -> float:
    radius_meters = 6_371_008.8
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    delta_phi = math.radians(lat2 - lat1)
    delta_lambda = math.radians(lon2 - lon1)

    a = (
        math.sin(delta_phi / 2.0) ** 2
        + math.cos(phi1)
        * math.cos(phi2)
        * math.sin(delta_lambda / 2.0) ** 2
    )
    return 2.0 * radius_meters * math.asin(math.sqrt(a))


def _link_restaurant_to_nearby_centers(
    connection: sqlite3.Connection,
    restaurant_key: str,
    latitude: float,
    longitude: float,
) -> None:
    centers = connection.execute(
        """
        SELECT center_id, latitude, longitude
        FROM waiting_centers
        """
    ).fetchall()

    for center in centers:
        distance = _distance_meters(
            latitude,
            longitude,
            float(center["latitude"]),
            float(center["longitude"]),
        )
        if distance > RESTAURANT_CENTER_LINK_RADIUS_METERS:
            continue

        connection.execute(
            """
            INSERT INTO waiting_center_restaurants (
                center_id,
                restaurant_key,
                distance_meters
            )
            VALUES (?, ?, ?)
            ON CONFLICT(center_id, restaurant_key) DO UPDATE SET
                distance_meters = excluded.distance_meters
            """,
            (
                center["center_id"],
                restaurant_key,
                distance,
            ),
        )


def _link_center_to_nearby_restaurants(
    connection: sqlite3.Connection,
    center_id: str,
    latitude: float,
    longitude: float,
) -> None:
    restaurants = connection.execute(
        """
        SELECT restaurant_key, latitude, longitude
        FROM restaurants
        """
    ).fetchall()

    for restaurant in restaurants:
        distance = _distance_meters(
            latitude,
            longitude,
            float(restaurant["latitude"]),
            float(restaurant["longitude"]),
        )
        if distance > RESTAURANT_CENTER_LINK_RADIUS_METERS:
            continue

        connection.execute(
            """
            INSERT INTO waiting_center_restaurants (
                center_id,
                restaurant_key,
                distance_meters
            )
            VALUES (?, ?, ?)
            ON CONFLICT(center_id, restaurant_key) DO UPDATE SET
                distance_meters = excluded.distance_meters
            """,
            (
                center_id,
                restaurant["restaurant_key"],
                distance,
            ),
        )


@app.post("/restaurants/observations")
def receive_restaurant_observation(
    payload: dict[str, Any],
) -> dict[str, Any]:
    """Store a GPS-observed restaurant coordinate and update its aggregate."""
    observation_id = payload.get("observation_id")
    restaurant_name = payload.get("restaurant_name")
    place_id = payload.get("restaurant_place_id")

    if not isinstance(observation_id, str) or not observation_id.strip():
        raise HTTPException(
            status_code=422,
            detail="observation_id is required.",
        )

    if not isinstance(restaurant_name, str) or not restaurant_name.strip():
        raise HTTPException(
            status_code=422,
            detail="restaurant_name is required.",
        )

    latitude = _require_coordinate(
        payload.get("latitude"),
        "latitude",
        -90.0,
        90.0,
    )
    longitude = _require_coordinate(
        payload.get("longitude"),
        "longitude",
        -180.0,
        180.0,
    )

    observed_at = payload.get("observed_at_wall_time_ms")
    if not isinstance(observed_at, int) or observed_at <= 0:
        raise HTTPException(
            status_code=422,
            detail="observed_at_wall_time_ms must be a positive integer.",
        )

    accuracy = payload.get("accuracy_meters")
    if accuracy is not None:
        try:
            accuracy = float(accuracy)
        except (TypeError, ValueError):
            accuracy = None

    coordinate_source = str(
        payload.get("coordinate_source") or "GPS_AT_RESTAURANT"
    )
    restaurant_key = _normalized_restaurant_key(
        place_id,
        restaurant_name,
    )
    received_at = datetime.now(timezone.utc).isoformat()
    raw_json = json.dumps(
        payload,
        ensure_ascii=False,
        separators=(",", ":"),
    )

    with get_connection() as connection:
        existing_observation = connection.execute(
            """
            SELECT observation_id
            FROM restaurant_observations
            WHERE observation_id = ?
            """,
            (observation_id,),
        ).fetchone()

        if existing_observation is not None:
            return {
                "status": "duplicate",
                "restaurant_key": restaurant_key,
            }

        connection.execute(
            """
            INSERT INTO restaurant_observations (
                observation_id,
                offer_id,
                restaurant_key,
                restaurant_place_id,
                restaurant_name,
                latitude,
                longitude,
                accuracy_meters,
                observed_at_wall_time_ms,
                coordinate_source,
                received_at,
                raw_json
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                observation_id,
                payload.get("offer_id"),
                restaurant_key,
                place_id,
                restaurant_name.strip(),
                latitude,
                longitude,
                accuracy,
                observed_at,
                coordinate_source,
                received_at,
                raw_json,
            ),
        )

        existing = connection.execute(
            """
            SELECT
                latitude,
                longitude,
                observation_count
            FROM restaurants
            WHERE restaurant_key = ?
            """,
            (restaurant_key,),
        ).fetchone()

        if existing is None:
            connection.execute(
                """
                INSERT INTO restaurants (
                    restaurant_key,
                    restaurant_place_id,
                    restaurant_name,
                    latitude,
                    longitude,
                    observation_count,
                    first_observed_at_wall_time_ms,
                    last_observed_at_wall_time_ms,
                    last_accuracy_meters,
                    coordinate_source
                )
                VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?)
                """,
                (
                    restaurant_key,
                    place_id,
                    restaurant_name.strip(),
                    latitude,
                    longitude,
                    observed_at,
                    observed_at,
                    accuracy,
                    coordinate_source,
                ),
            )
            count = 1
        else:
            old_count = int(existing["observation_count"])
            count = old_count + 1
            new_latitude = (
                float(existing["latitude"]) * old_count + latitude
            ) / count
            new_longitude = (
                float(existing["longitude"]) * old_count + longitude
            ) / count

            connection.execute(
                """
                UPDATE restaurants
                SET
                    restaurant_place_id = COALESCE(?, restaurant_place_id),
                    restaurant_name = ?,
                    latitude = ?,
                    longitude = ?,
                    observation_count = ?,
                    last_observed_at_wall_time_ms = ?,
                    last_accuracy_meters = ?,
                    coordinate_source = ?
                WHERE restaurant_key = ?
                """,
                (
                    place_id,
                    restaurant_name.strip(),
                    new_latitude,
                    new_longitude,
                    count,
                    observed_at,
                    accuracy,
                    coordinate_source,
                    restaurant_key,
                ),
            )

        aggregate = connection.execute(
            """
            SELECT latitude, longitude
            FROM restaurants
            WHERE restaurant_key = ?
            """,
            (restaurant_key,),
        ).fetchone()

        if aggregate is not None:
            _link_restaurant_to_nearby_centers(
                connection=connection,
                restaurant_key=restaurant_key,
                latitude=float(aggregate["latitude"]),
                longitude=float(aggregate["longitude"]),
            )

        connection.commit()

    return {
        "status": "stored",
        "restaurant_key": restaurant_key,
        "observation_count": count,
    }


@app.get("/restaurants")
def restaurants(
    limit: int = Query(default=500, ge=1, le=5_000),
) -> dict[str, Any]:
    """Return GPS-observed restaurant coordinates."""
    with get_connection() as connection:
        rows = connection.execute(
            """
            SELECT
                restaurant_key,
                restaurant_place_id,
                restaurant_name,
                latitude,
                longitude,
                observation_count,
                first_observed_at_wall_time_ms,
                last_observed_at_wall_time_ms,
                last_accuracy_meters,
                coordinate_source
            FROM restaurants
            ORDER BY last_observed_at_wall_time_ms DESC
            LIMIT ?
            """,
            (limit,),
        ).fetchall()

    return {
        "count": len(rows),
        "restaurants": [dict(row) for row in rows],
    }


@app.post("/waiting-centers")
def upsert_waiting_center(
    payload: dict[str, Any],
) -> dict[str, str]:
    """Save or refresh one candidate waiting/shopping center."""
    center_id = payload.get("center_id")
    center_name = payload.get("center_name")

    if not isinstance(center_id, str) or not center_id.strip():
        raise HTTPException(status_code=422, detail="center_id is required.")
    if not isinstance(center_name, str) or not center_name.strip():
        raise HTTPException(status_code=422, detail="center_name is required.")

    latitude = _require_coordinate(
        payload.get("latitude"), "latitude", -90.0, 90.0
    )
    longitude = _require_coordinate(
        payload.get("longitude"), "longitude", -180.0, 180.0
    )
    source = str(payload.get("source") or "UNKNOWN")
    last_seen = payload.get("last_seen_at_wall_time_ms")
    now = datetime.now(timezone.utc).isoformat()

    with get_connection() as connection:
        connection.execute(
            """
            INSERT INTO waiting_centers (
                center_id,
                center_name,
                latitude,
                longitude,
                source,
                last_seen_at_wall_time_ms,
                created_at,
                updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(center_id) DO UPDATE SET
                center_name = excluded.center_name,
                latitude = excluded.latitude,
                longitude = excluded.longitude,
                source = excluded.source,
                last_seen_at_wall_time_ms = excluded.last_seen_at_wall_time_ms,
                updated_at = excluded.updated_at
            """,
            (
                center_id.strip(),
                center_name.strip(),
                latitude,
                longitude,
                source,
                last_seen,
                now,
                now,
            ),
        )

        _link_center_to_nearby_restaurants(
            connection=connection,
            center_id=center_id.strip(),
            latitude=latitude,
            longitude=longitude,
        )

        connection.commit()

    return {"status": "stored", "center_id": center_id.strip()}


@app.post("/waiting-center-restaurants")
def link_waiting_center_restaurant(
    payload: dict[str, Any],
) -> dict[str, str]:
    """Associate a saved restaurant with a waiting center."""
    center_id = payload.get("center_id")
    restaurant_key = payload.get("restaurant_key")

    if not isinstance(center_id, str) or not center_id.strip():
        raise HTTPException(status_code=422, detail="center_id is required.")
    if not isinstance(restaurant_key, str) or not restaurant_key.strip():
        raise HTTPException(status_code=422, detail="restaurant_key is required.")

    distance = payload.get("distance_meters")
    if distance is not None:
        try:
            distance = float(distance)
        except (TypeError, ValueError):
            distance = None

    with get_connection() as connection:
        center = connection.execute(
            "SELECT center_id FROM waiting_centers WHERE center_id = ?",
            (center_id.strip(),),
        ).fetchone()
        restaurant = connection.execute(
            "SELECT restaurant_key FROM restaurants WHERE restaurant_key = ?",
            (restaurant_key.strip(),),
        ).fetchone()

        if center is None:
            raise HTTPException(status_code=404, detail="Unknown center_id.")
        if restaurant is None:
            raise HTTPException(status_code=404, detail="Unknown restaurant_key.")

        connection.execute(
            """
            INSERT INTO waiting_center_restaurants (
                center_id,
                restaurant_key,
                distance_meters
            )
            VALUES (?, ?, ?)
            ON CONFLICT(center_id, restaurant_key) DO UPDATE SET
                distance_meters = excluded.distance_meters
            """,
            (center_id.strip(), restaurant_key.strip(), distance),
        )
        connection.commit()

    return {"status": "linked"}


@app.post("/waiting-sessions")
def receive_waiting_session(
    payload: dict[str, Any],
) -> dict[str, str]:
    """Store one between-order wait observation."""
    waiting_session_id = payload.get("waiting_session_id")
    next_offer_time = payload.get("next_offer_detected_at_wall_time_ms")

    if not isinstance(waiting_session_id, str) or not waiting_session_id.strip():
        raise HTTPException(
            status_code=422,
            detail="waiting_session_id is required.",
        )
    if not isinstance(next_offer_time, int) or next_offer_time <= 0:
        raise HTTPException(
            status_code=422,
            detail="next_offer_detected_at_wall_time_ms is required.",
        )

    wait_ms = payload.get("wait_at_center_ms")
    excluded = bool(payload.get("exclude_from_learning", False))

    if wait_ms is not None:
        if not isinstance(wait_ms, int) or wait_ms < 0:
            raise HTTPException(
                status_code=422,
                detail="wait_at_center_ms must be a non-negative integer or null.",
            )
    else:
        excluded = True

    center_id = payload.get("center_id")
    if not isinstance(center_id, str) or not center_id.strip():
        center_id = None
        excluded = True

    received_at = datetime.now(timezone.utc).isoformat()
    raw_json = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))

    with get_connection() as connection:
        connection.execute(
            """
            INSERT INTO waiting_sessions (
                waiting_session_id,
                previous_offer_id,
                next_offer_id,
                delivery_completed_at_wall_time_ms,
                center_id,
                center_name,
                center_latitude,
                center_longitude,
                recommended_center_id,
                center_arrived_at_wall_time_ms,
                arrival_latitude,
                arrival_longitude,
                next_offer_detected_at_wall_time_ms,
                travel_to_center_ms,
                wait_at_center_ms,
                offer_before_arrival,
                exclude_from_learning,
                exclusion_reason,
                received_at,
                raw_json
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(waiting_session_id) DO NOTHING
            """,
            (
                waiting_session_id.strip(),
                payload.get("previous_offer_id"),
                payload.get("next_offer_id"),
                payload.get("delivery_completed_at_wall_time_ms"),
                center_id,
                payload.get("center_name"),
                payload.get("center_latitude"),
                payload.get("center_longitude"),
                payload.get("recommended_center_id"),
                payload.get("center_arrived_at_wall_time_ms"),
                payload.get("arrival_latitude"),
                payload.get("arrival_longitude"),
                next_offer_time,
                payload.get("travel_to_center_ms"),
                wait_ms,
                int(bool(payload.get("offer_before_arrival", False))),
                int(excluded),
                payload.get("exclusion_reason"),
                received_at,
                raw_json,
            ),
        )
        connection.commit()

    return {"status": "stored", "waiting_session_id": waiting_session_id.strip()}


def _waiting_metric(values_ms: list[int]) -> dict[str, Any] | None:
    if not values_ms:
        return None

    values_minutes = [value / 60_000.0 for value in values_ms]
    return {
        "samples": len(values_minutes),
        "mean_minutes": sum(values_minutes) / len(values_minutes),
        "median_minutes": statistics.median(values_minutes),
        "minimum_minutes": min(values_minutes),
        "maximum_minutes": max(values_minutes),
    }


@app.get("/waiting-centers/stats")
def waiting_center_stats() -> dict[str, Any]:
    """Return historical wait and saved restaurant counts by center."""
    with get_connection() as connection:
        centers = connection.execute(
            """
            SELECT
                c.center_id,
                c.center_name,
                c.latitude,
                c.longitude,
                COUNT(DISTINCT cr.restaurant_key) AS restaurant_count
            FROM waiting_centers AS c
            LEFT JOIN waiting_center_restaurants AS cr
                ON cr.center_id = c.center_id
            GROUP BY c.center_id
            ORDER BY c.center_name COLLATE NOCASE
            """
        ).fetchall()

        valid_wait_rows = connection.execute(
            """
            SELECT
                center_id,
                wait_at_center_ms
            FROM waiting_sessions
            WHERE
                exclude_from_learning = 0
                AND center_id IS NOT NULL
                AND wait_at_center_ms IS NOT NULL
                AND wait_at_center_ms >= 0
            ORDER BY next_offer_detected_at_wall_time_ms ASC
            """
        ).fetchall()

    waits_by_center: dict[str, list[int]] = {}
    all_waits: list[int] = []
    for row in valid_wait_rows:
        center_id = str(row["center_id"])
        wait_ms = int(row["wait_at_center_ms"])
        waits_by_center.setdefault(center_id, []).append(wait_ms)
        all_waits.append(wait_ms)

    global_metric = _waiting_metric(all_waits)
    global_mean = (
        float(global_metric["mean_minutes"])
        if global_metric is not None
        else None
    )
    prior_strength = 10.0

    result: list[dict[str, Any]] = []
    for center in centers:
        center_values = waits_by_center.get(str(center["center_id"]), [])
        metric = _waiting_metric(center_values)
        samples = len(center_values)

        blended_wait = None
        if metric is not None:
            center_mean = float(metric["mean_minutes"])
            if global_mean is None:
                blended_wait = center_mean
            else:
                learned_weight = samples / (samples + prior_strength)
                blended_wait = (
                    learned_weight * center_mean
                    + (1.0 - learned_weight) * global_mean
                )
        elif global_mean is not None:
            blended_wait = global_mean

        result.append(
            {
                "center_id": center["center_id"],
                "center_name": center["center_name"],
                "latitude": center["latitude"],
                "longitude": center["longitude"],
                "restaurant_count": int(center["restaurant_count"]),
                "historical_wait": metric,
                "blended_historical_wait_minutes": blended_wait,
                "historical_wait_confidence": samples / (samples + prior_strength),
            }
        )

    return {
        "center_count": len(result),
        "global_historical_wait": global_metric,
        "prior_strength_samples": prior_strength,
        "centers": result,
    }


@app.get("/waiting-sessions/recent")
def recent_waiting_sessions(
    limit: int = Query(default=50, ge=1, le=500),
) -> dict[str, Any]:
    with get_connection() as connection:
        rows = connection.execute(
            """
            SELECT *
            FROM waiting_sessions
            ORDER BY next_offer_detected_at_wall_time_ms DESC
            LIMIT ?
            """,
            (limit,),
        ).fetchall()

    return {
        "count": len(rows),
        "waiting_sessions": [dict(row) for row in rows],
    }


@app.get("/orders/count")
def order_count() -> dict[str, int]:
    """Return the number of unique orders stored."""
    with get_connection() as connection:
        row = connection.execute(
            """
            SELECT
                COUNT(*) AS count
            FROM orders
            """
        ).fetchone()

    return {
        "count": int(
            row["count"]
        ) if row else 0
    }


@app.get("/orders/recent")
def recent_orders(
    limit: int = Query(
        default=20,
        ge=1,
        le=100,
    ),
) -> dict[str, Any]:
    """Return summaries of the most recently received orders."""
    with get_connection() as connection:
        rows = connection.execute(
            """
            SELECT
                offer_id,
                received_at,
                schema_version,
                restaurant_place_id,
                restaurant_name,
                offered_payout,
                displayed_total_miles,
                engine_version,
                exclude_from_training,
                quality_note
            FROM orders
            ORDER BY received_at DESC
            LIMIT ?
            """,
            (
                limit,
            ),
        ).fetchall()

    return {
        "count": len(
            rows
        ),
        "orders": [
            {
                "offer_id":
                    row["offer_id"],

                "received_at":
                    row["received_at"],

                "schema_version":
                    row["schema_version"],

                "restaurant_place_id":
                    row["restaurant_place_id"],

                "restaurant_name":
                    row["restaurant_name"],

                "offered_payout":
                    row["offered_payout"],

                "displayed_total_miles":
                    row["displayed_total_miles"],

                "engine_version":
                    row["engine_version"],

                "exclude_from_training":
                    bool(
                        row[
                            "exclude_from_training"
                        ]
                    ),

                "quality_note":
                    row["quality_note"],
            }
            for row in rows
        ],
    }


@app.get("/training-data/summary")
def training_data_summary() -> dict[str, int]:
    """Show how many orders are eligible or excluded from training."""
    with get_connection() as connection:
        row = connection.execute(
            """
            SELECT
                COUNT(*) AS total,
                SUM(
                    CASE
                        WHEN exclude_from_training = 0
                        THEN 1
                        ELSE 0
                    END
                ) AS eligible,
                SUM(
                    CASE
                        WHEN exclude_from_training = 1
                        THEN 1
                        ELSE 0
                    END
                ) AS excluded
            FROM orders
            """
        ).fetchone()

    return {
        "total": int(
            row["total"] or 0
        ),
        "eligible": int(
            row["eligible"] or 0
        ),
        "excluded": int(
            row["excluded"] or 0
        ),
    }


@app.post("/orders/mark-existing-as-practice")
def mark_existing_as_practice() -> dict[str, int | str]:
    """
    Exclude every order currently in the database.

    This is intended for clearing the current development/practice
    records before genuine delivery data collection begins.
    """
    updated_count = 0

    with get_connection() as connection:
        rows = connection.execute(
            """
            SELECT
                offer_id,
                raw_json
            FROM orders
            """
        ).fetchall()

        for row in rows:
            try:
                payload = json.loads(
                    row["raw_json"]
                )
            except json.JSONDecodeError:
                continue

            offer = payload.get(
                "offer"
            )

            if not isinstance(
                offer,
                dict,
            ):
                continue

            offer[
                "exclude_from_training"
            ] = True

            current_note = offer.get(
                "quality_note"
            )

            if (
                not isinstance(
                    current_note,
                    str,
                )
                or not current_note.strip()
            ):
                offer[
                    "quality_note"
                ] = "Practice/test data"

            connection.execute(
                """
                UPDATE orders
                SET
                    exclude_from_training = 1,
                    quality_note = ?,
                    raw_json = ?
                WHERE offer_id = ?
                """,
                (
                    offer.get(
                        "quality_note"
                    ),
                    json.dumps(
                        payload,
                        ensure_ascii=False,
                        separators=(",", ":"),
                    ),
                    row["offer_id"],
                ),
            )

            updated_count += 1

        connection.commit()

    return {
        "status": "marked_as_practice",
        "updated_count": updated_count,
    }


@app.patch("/orders/{offer_id}/training-status")
def update_training_status(
    offer_id: str,
    update: TrainingStatusUpdate,
) -> dict[str, Any]:
    """Update whether one stored order may be used for training."""
    with get_connection() as connection:
        row = connection.execute(
            """
            SELECT
                raw_json
            FROM orders
            WHERE offer_id = ?
            """,
            (
                offer_id,
            ),
        ).fetchone()

        if row is None:
            raise HTTPException(
                status_code=404,
                detail="Order not found.",
            )

        try:
            payload = json.loads(
                row["raw_json"]
            )
        except json.JSONDecodeError as exc:
            raise HTTPException(
                status_code=500,
                detail="Stored order JSON is invalid.",
            ) from exc

        offer = payload.get(
            "offer"
        )

        if not isinstance(
            offer,
            dict,
        ):
            raise HTTPException(
                status_code=500,
                detail="Stored order has no valid offer object.",
            )

        offer[
            "exclude_from_training"
        ] = update.exclude_from_training

        offer[
            "quality_note"
        ] = update.quality_note

        connection.execute(
            """
            UPDATE orders
            SET
                exclude_from_training = ?,
                quality_note = ?,
                raw_json = ?
            WHERE offer_id = ?
            """,
            (
                int(
                    update.exclude_from_training
                ),
                update.quality_note,
                json.dumps(
                    payload,
                    ensure_ascii=False,
                    separators=(",", ":"),
                ),
                offer_id,
            ),
        )
        connection.commit()

    return {
        "status": "updated",
        "offer_id": offer_id,
        "exclude_from_training":
            update.exclude_from_training,
        "quality_note":
            update.quality_note,
    }


@app.get("/orders/{offer_id}")
def get_order(
    offer_id: str,
) -> dict[str, Any]:
    """Return the full original JSON for one stored order."""
    with get_connection() as connection:
        row = connection.execute(
            """
            SELECT
                received_at,
                raw_json
            FROM orders
            WHERE offer_id = ?
            """,
            (
                offer_id,
            ),
        ).fetchone()

    if row is None:
        raise HTTPException(
            status_code=404,
            detail="Order not found.",
        )

    try:
        payload = json.loads(
            row["raw_json"]
        )
    except json.JSONDecodeError as exc:
        raise HTTPException(
            status_code=500,
            detail="Stored order JSON is invalid.",
        ) from exc

    return {
        "received_at":
            row["received_at"],

        "order":
            payload,
    }


def _decode_raw_json_rows(
    rows: list[sqlite3.Row],
) -> list[dict[str, Any]]:
    """Decode raw_json from linked telemetry tables without losing fields."""
    decoded: list[dict[str, Any]] = []

    for row in rows:
        try:
            payload = json.loads(
                row["raw_json"]
            )
        except (json.JSONDecodeError, TypeError):
            payload = {
                "decode_error": True,
                "raw_json": row["raw_json"],
            }

        decoded.append(
            payload
        )

    return decoded


@app.get("/orders/{offer_id}/reconstruction")
def reconstruct_order(
    offer_id: str,
) -> dict[str, Any]:
    """Return every currently linkable record for one DashTool offer ID."""
    with get_connection() as connection:
        order_row = connection.execute(
            """
            SELECT
                received_at,
                raw_json
            FROM orders
            WHERE offer_id = ?
            """,
            (offer_id,),
        ).fetchone()

        if order_row is None:
            raise HTTPException(
                status_code=404,
                detail="Order not found.",
            )

        restaurant_rows = connection.execute(
            """
            SELECT raw_json
            FROM restaurant_observations
            WHERE offer_id = ?
            ORDER BY observed_at_wall_time_ms ASC
            """,
            (offer_id,),
        ).fetchall()

        customer_map_rows = connection.execute(
            """
            SELECT raw_json
            FROM customer_map_samples
            WHERE offer_id = ?
            ORDER BY received_at ASC
            """,
            (offer_id,),
        ).fetchall()

        offer_wait_rows = connection.execute(
            """
            SELECT raw_json
            FROM offer_wait_samples
            WHERE next_offer_id = ?
            ORDER BY next_offer_detected_at_wall_time_ms ASC
            """,
            (offer_id,),
        ).fetchall()

        waiting_before_rows = connection.execute(
            """
            SELECT raw_json
            FROM waiting_sessions
            WHERE next_offer_id = ?
            ORDER BY next_offer_detected_at_wall_time_ms ASC
            """,
            (offer_id,),
        ).fetchall()

        waiting_after_rows = connection.execute(
            """
            SELECT raw_json
            FROM waiting_sessions
            WHERE previous_offer_id = ?
            ORDER BY next_offer_detected_at_wall_time_ms ASC
            """,
            (offer_id,),
        ).fetchall()

    try:
        order_payload = json.loads(
            order_row["raw_json"]
        )
    except json.JSONDecodeError as exc:
        raise HTTPException(
            status_code=500,
            detail="Stored order JSON is invalid.",
        ) from exc

    restaurant_observations = _decode_raw_json_rows(
        restaurant_rows
    )
    customer_map_samples = _decode_raw_json_rows(
        customer_map_rows
    )
    offer_wait_samples = _decode_raw_json_rows(
        offer_wait_rows
    )
    waiting_before_offer = _decode_raw_json_rows(
        waiting_before_rows
    )
    waiting_after_offer = _decode_raw_json_rows(
        waiting_after_rows
    )

    decision_telemetry = order_payload.get(
        "decision_telemetry"
    )

    return {
        "offer_id": offer_id,
        "order_received_at": order_row["received_at"],
        "order": order_payload,
        "linked_data": {
            "restaurant_observations": restaurant_observations,
            "customer_map_samples": customer_map_samples,
            "offer_wait_samples_before_offer": offer_wait_samples,
            "waiting_sessions_before_offer": waiting_before_offer,
            "waiting_sessions_after_offer": waiting_after_offer,
        },
        "completeness": {
            "has_decision_telemetry": isinstance(
                decision_telemetry,
                dict,
            ),
            "restaurant_observation_count": len(
                restaurant_observations
            ),
            "customer_map_sample_count": len(
                customer_map_samples
            ),
            "offer_wait_sample_count": len(
                offer_wait_samples
            ),
            "waiting_session_before_count": len(
                waiting_before_offer
            ),
            "waiting_session_after_count": len(
                waiting_after_offer
            ),
        },
    }


def as_finite_float(
    value: Any,
) -> float | None:
    """Convert a JSON value to a finite float when possible."""
    if isinstance(
        value,
        bool,
    ):
        return None

    if not isinstance(
        value,
        (int, float),
    ):
        return None

    number = float(
        value
    )

    if (
        number != number
        or number in (
            float("inf"),
            float("-inf"),
        )
    ):
        return None

    return number


def duration_minutes(
    derived_durations: dict[str, Any],
    key: str,
    maximum_minutes: float,
) -> float | None:
    """
    Read one nonnegative duration and convert milliseconds to minutes.

    The maximum is deliberately broad. It filters obviously corrupted
    values without forcing normal deliveries into narrow assumptions.
    """
    milliseconds = as_finite_float(
        derived_durations.get(
            key
        )
    )

    if milliseconds is None:
        return None

    minutes = milliseconds / 60_000.0

    if (
        minutes < 0.0
        or minutes > maximum_minutes
    ):
        return None

    return minutes


def calculate_metric(
    values: list[float],
    ewma_alpha: float,
) -> dict[str, int | float | None]:
    """Return sample count, mean, median, and chronological EWMA."""
    if not values:
        return {
            "samples": 0,
            "mean": None,
            "median": None,
            "rolling_ewma": None,
        }

    sorted_values = sorted(
        values
    )

    count = len(
        values
    )

    midpoint = count // 2

    if count % 2 == 1:
        median = sorted_values[
            midpoint
        ]
    else:
        median = (
            sorted_values[
                midpoint - 1
            ]
            + sorted_values[
                midpoint
            ]
        ) / 2.0

    rolling_value = values[0]

    for value in values[1:]:
        rolling_value = (
            ewma_alpha
            * value
            + (
                1.0
                - ewma_alpha
            )
            * rolling_value
        )

    return {
        "samples": count,
        "mean": sum(
            values
        ) / count,
        "median": median,
        "rolling_ewma": rolling_value,
    }


def load_eligible_training_orders() -> list[dict[str, Any]]:
    """
    Load non-excluded orders in chronological order.

    Raw JSON remains the source of truth for lifecycle durations.
    """
    with get_connection() as connection:
        rows = connection.execute(
            """
            SELECT
                offer_id,
                received_at,
                restaurant_place_id,
                restaurant_name,
                raw_json
            FROM orders
            WHERE exclude_from_training = 0
            ORDER BY received_at ASC
            """
        ).fetchall()

    loaded_orders: list[
        dict[str, Any]
    ] = []

    for row in rows:
        try:
            payload = json.loads(
                row["raw_json"]
            )
        except json.JSONDecodeError:
            continue

        offer = payload.get(
            "offer"
        )

        durations = payload.get(
            "derived_durations_ms"
        )

        route_snapshot = payload.get(
            "route_snapshot"
        )

        if not isinstance(
            offer,
            dict,
        ):
            offer = {}

        if not isinstance(
            durations,
            dict,
        ):
            durations = {}

        if not isinstance(
            route_snapshot,
            dict,
        ):
            route_snapshot = {}

        loaded_orders.append(
            {
                "offer_id":
                    row["offer_id"],

                "received_at":
                    row["received_at"],

                "restaurant_place_id":
                    row[
                        "restaurant_place_id"
                    ],

                "restaurant_name":
                    row[
                        "restaurant_name"
                    ],

                "offer":
                    offer,

                "durations":
                    durations,

                "route_snapshot":
                    route_snapshot,
            }
        )

    return loaded_orders


@app.get("/analytics/summary")
def analytics_summary(
    ewma_alpha: float = Query(
        default=0.20,
        gt=0.0,
        le=1.0,
    ),
) -> dict[str, Any]:
    """
    Calculate initial global timing estimates from eligible orders only.

    Newer values influence rolling_ewma more strongly than older values.
    """
    orders = load_eligible_training_orders()

    drive_to_restaurant_values: list[
        float
    ] = []

    restaurant_wait_values: list[
        float
    ] = []

    drive_to_customer_values: list[
        float
    ] = []

    dropoff_values: list[
        float
    ] = []

    total_order_values: list[
        float
    ] = []

    route_error_values: list[
        float
    ] = []

    route_ratio_values: list[
        float
    ] = []

    usable_offer_ids: set[
        str
    ] = set()

    for order in orders:
        durations = order[
            "durations"
        ]

        drive_to_restaurant = duration_minutes(
            derived_durations=durations,
            key="drive_to_restaurant_ms",
            maximum_minutes=180.0,
        )

        restaurant_wait = duration_minutes(
            derived_durations=durations,
            key="restaurant_wait_ms",
            maximum_minutes=120.0,
        )

        drive_to_customer = duration_minutes(
            derived_durations=durations,
            key="drive_to_customer_ms",
            maximum_minutes=180.0,
        )

        dropoff = duration_minutes(
            derived_durations=durations,
            key="dropoff_ms",
            maximum_minutes=60.0,
        )

        total_order = duration_minutes(
            derived_durations=durations,
            key="total_order_ms",
            maximum_minutes=360.0,
        )

        metric_values = (
            (
                drive_to_restaurant,
                drive_to_restaurant_values,
            ),
            (
                restaurant_wait,
                restaurant_wait_values,
            ),
            (
                drive_to_customer,
                drive_to_customer_values,
            ),
            (
                dropoff,
                dropoff_values,
            ),
            (
                total_order,
                total_order_values,
            ),
        )

        used_order = False

        for (
            value,
            destination,
        ) in metric_values:
            if value is not None:
                destination.append(
                    value
                )

                used_order = True

        route_eta = as_finite_float(
            order[
                "route_snapshot"
            ].get(
                "eta_minutes"
            )
        )

        if (
            drive_to_restaurant is not None
            and route_eta is not None
            and route_eta > 0.0
            and route_eta <= 180.0
        ):
            route_error_values.append(
                drive_to_restaurant
                - route_eta
            )

            route_ratio_values.append(
                drive_to_restaurant
                / route_eta
            )

            used_order = True

        if used_order:
            usable_offer_ids.add(
                order["offer_id"]
            )

    return {
        "eligible_orders":
            len(
                orders
            ),

        "usable_orders":
            len(
                usable_offer_ids
            ),

        "ewma_alpha":
            ewma_alpha,

        "global":
            {
                "drive_to_restaurant_minutes":
                    calculate_metric(
                        drive_to_restaurant_values,
                        ewma_alpha,
                    ),

                "restaurant_wait_minutes":
                    calculate_metric(
                        restaurant_wait_values,
                        ewma_alpha,
                    ),

                "drive_to_customer_minutes":
                    calculate_metric(
                        drive_to_customer_values,
                        ewma_alpha,
                    ),

                "dropoff_minutes":
                    calculate_metric(
                        dropoff_values,
                        ewma_alpha,
                    ),

                "total_order_minutes":
                    calculate_metric(
                        total_order_values,
                        ewma_alpha,
                    ),

                "route_prediction_error_minutes":
                    calculate_metric(
                        route_error_values,
                        ewma_alpha,
                    ),

                "actual_to_predicted_route_ratio":
                    calculate_metric(
                        route_ratio_values,
                        ewma_alpha,
                    ),
            },
    }


@app.get("/analytics/restaurants")
def restaurant_analytics(
    minimum_samples: int = Query(
        default=1,
        ge=1,
        le=1_000,
    ),
    ewma_alpha: float = Query(
        default=0.20,
        gt=0.0,
        le=1.0,
    ),
) -> dict[str, Any]:
    """Calculate restaurant-specific wait estimates from eligible orders."""
    orders = load_eligible_training_orders()

    grouped: dict[
        str,
        dict[str, Any],
    ] = {}

    for order in orders:
        place_id = order[
            "restaurant_place_id"
        ]

        restaurant_name = order[
            "restaurant_name"
        ]

        key = (
            str(
                place_id
            )
            if place_id
            else "name:"
            + str(
                restaurant_name
                or "unknown"
            ).strip().lower()
        )

        wait_minutes = duration_minutes(
            derived_durations=order[
                "durations"
            ],
            key="restaurant_wait_ms",
            maximum_minutes=120.0,
        )

        if wait_minutes is None:
            continue

        group = grouped.setdefault(
            key,
            {
                "restaurant_place_id":
                    place_id,

                "restaurant_name":
                    restaurant_name,

                "values":
                    [],
            },
        )

        group[
            "values"
        ].append(
            wait_minutes
        )

    restaurants: list[
        dict[str, Any]
    ] = []

    for group in grouped.values():
        values = group[
            "values"
        ]

        if (
            len(
                values
            )
            < minimum_samples
        ):
            continue

        restaurants.append(
            {
                "restaurant_place_id":
                    group[
                        "restaurant_place_id"
                    ],

                "restaurant_name":
                    group[
                        "restaurant_name"
                    ],

                "restaurant_wait_minutes":
                    calculate_metric(
                        values,
                        ewma_alpha,
                    ),
            }
        )

    restaurants.sort(
        key=lambda item: (
            -int(
                item[
                    "restaurant_wait_minutes"
                ][
                    "samples"
                ]
            ),
            str(
                item[
                    "restaurant_name"
                ]
                or ""
            ).lower(),
        )
    )

    return {
        "restaurant_count":
            len(
                restaurants
            ),

        "minimum_samples":
            minimum_samples,

        "ewma_alpha":
            ewma_alpha,

        "restaurants":
            restaurants,
    }


def clamp(
    value: float,
    minimum: float,
    maximum: float,
) -> float:
    return max(
        minimum,
        min(
            maximum,
            value,
        ),
    )


def latest_engine_config_row() -> sqlite3.Row:
    with get_connection() as connection:
        row = connection.execute(
            """
            SELECT
                engine_version,
                generated_at,
                source_eligible_orders,
                source_usable_orders,
                config_json
            FROM engine_configs
            ORDER BY engine_version DESC
            LIMIT 1
            """
        ).fetchone()

    if row is None:
        raise HTTPException(
            status_code=500,
            detail="No engine configuration exists.",
        )

    return row


def parse_engine_config(
    row: sqlite3.Row,
) -> dict[str, Any]:
    try:
        return json.loads(
            row["config_json"]
        )
    except json.JSONDecodeError as exc:
        raise HTTPException(
            status_code=500,
            detail="Stored engine configuration is invalid.",
        ) from exc


def parameter_signature(
    config: dict[str, Any],
) -> str:
    """
    Return a canonical representation of values that affect scoring.

    Metadata such as generation time and source-order counts is excluded,
    so merely opening the generator without a parameter change does not
    create a new engine version.
    """
    parameter_only = {
        "global":
            config.get(
                "global"
            ),

        "restaurant_waits":
            config.get(
                "restaurant_waits"
            ),

        "scoring":
            config.get(
                "scoring"
            ),

        "travel_model":
            config.get(
                "travel_model"
            ),

        "features":
            config.get(
                "features"
            ),

        "learning":
            config.get(
                "learning"
            ),
    }

    return json.dumps(
        parameter_only,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def calculate_route_correction(
    orders: list[dict[str, Any]],
    minimum_samples: int,
) -> tuple[float, float, int]:
    """
    Fit actual restaurant drive time as:

        actual = predicted * multiplier + fixed_delay

    The learned correction is applied only when enough valid route pairs
    exist. Broad clamps prevent a small or unusual data set from producing
    an unsafe correction.
    """
    route_pairs: list[
        tuple[float, float]
    ] = []

    for order in orders:
        actual_minutes = duration_minutes(
            derived_durations=order[
                "durations"
            ],
            key="drive_to_restaurant_ms",
            maximum_minutes=180.0,
        )

        predicted_minutes = as_finite_float(
            order[
                "route_snapshot"
            ].get(
                "eta_minutes"
            )
        )

        if (
            actual_minutes is None
            or predicted_minutes is None
            or predicted_minutes <= 0.0
            or predicted_minutes > 180.0
        ):
            continue

        route_pairs.append(
            (
                predicted_minutes,
                actual_minutes,
            )
        )

    sample_count = len(
        route_pairs
    )

    if sample_count < minimum_samples:
        return (
            1.0,
            0.0,
            sample_count,
        )

    predicted_mean = sum(
        pair[0]
        for pair in route_pairs
    ) / sample_count

    actual_mean = sum(
        pair[1]
        for pair in route_pairs
    ) / sample_count

    denominator = sum(
        (
            predicted
            - predicted_mean
        )
        ** 2
        for (
            predicted,
            _,
        ) in route_pairs
    )

    if denominator > 1e-9:
        numerator = sum(
            (
                predicted
                - predicted_mean
            )
            * (
                actual
                - actual_mean
            )
            for (
                predicted,
                actual,
            ) in route_pairs
        )

        multiplier = numerator / denominator
        fixed_delay = (
            actual_mean
            - multiplier
            * predicted_mean
        )
    else:
        ratios = [
            actual / predicted
            for (
                predicted,
                actual,
            ) in route_pairs
        ]

        multiplier = sum(
            ratios
        ) / len(
            ratios
        )

        fixed_delay = 0.0

    return (
        clamp(
            multiplier,
            0.50,
            1.75,
        ),
        clamp(
            fixed_delay,
            -2.0,
            10.0,
        ),
        sample_count,
    )


def build_engine_candidate(
    minimum_global_samples: int,
    minimum_restaurant_samples: int,
    ewma_alpha: float,
    restaurant_prior_strength: float,
) -> dict[str, Any]:
    orders = load_eligible_training_orders()

    restaurant_wait_values: list[
        float
    ] = []

    dropoff_values: list[
        float
    ] = []

    usable_offer_ids: set[
        str
    ] = set()

    grouped_restaurants: dict[
        str,
        dict[str, Any],
    ] = {}

    for order in orders:
        durations = order[
            "durations"
        ]

        restaurant_wait = duration_minutes(
            derived_durations=durations,
            key="restaurant_wait_ms",
            maximum_minutes=120.0,
        )

        dropoff = duration_minutes(
            derived_durations=durations,
            key="dropoff_ms",
            maximum_minutes=60.0,
        )

        if restaurant_wait is not None:
            restaurant_wait_values.append(
                restaurant_wait
            )

            usable_offer_ids.add(
                order["offer_id"]
            )

            place_id = order[
                "restaurant_place_id"
            ]

            restaurant_name = order[
                "restaurant_name"
            ]

            key = (
                str(
                    place_id
                )
                if place_id
                else "name:"
                + str(
                    restaurant_name
                    or "unknown"
                ).strip().lower()
            )

            group = grouped_restaurants.setdefault(
                key,
                {
                    "restaurant_place_id":
                        place_id,

                    "restaurant_name":
                        restaurant_name,

                    "values":
                        [],
                },
            )

            group[
                "values"
            ].append(
                restaurant_wait
            )

        if dropoff is not None:
            dropoff_values.append(
                dropoff
            )

            usable_offer_ids.add(
                order["offer_id"]
            )

    restaurant_wait_metric = calculate_metric(
        restaurant_wait_values,
        ewma_alpha,
    )

    dropoff_metric = calculate_metric(
        dropoff_values,
        ewma_alpha,
    )

    global_restaurant_wait = float(
        BASELINE_ENGINE_PARAMETERS[
            "global"
        ][
            "restaurant_wait_minutes"
        ]
    )

    if (
        restaurant_wait_metric["samples"]
        >= minimum_global_samples
        and restaurant_wait_metric[
            "rolling_ewma"
        ]
        is not None
    ):
        global_restaurant_wait = clamp(
            float(
                restaurant_wait_metric[
                    "rolling_ewma"
                ]
            ),
            0.0,
            60.0,
        )

    global_dropoff = float(
        BASELINE_ENGINE_PARAMETERS[
            "global"
        ][
            "customer_dropoff_minutes"
        ]
    )

    if (
        dropoff_metric["samples"]
        >= minimum_global_samples
        and dropoff_metric[
            "rolling_ewma"
        ]
        is not None
    ):
        global_dropoff = clamp(
            float(
                dropoff_metric[
                    "rolling_ewma"
                ]
            ),
            0.0,
            30.0,
        )

    (
        route_multiplier,
        route_fixed_delay,
        route_sample_count,
    ) = calculate_route_correction(
        orders=orders,
        minimum_samples=minimum_global_samples,
    )

    restaurant_waits: dict[
        str,
        dict[str, Any],
    ] = {}

    for (
        key,
        group,
    ) in grouped_restaurants.items():
        values = group[
            "values"
        ]

        sample_count = len(
            values
        )

        if (
            sample_count
            < minimum_restaurant_samples
        ):
            continue

        specific_metric = calculate_metric(
            values,
            ewma_alpha,
        )

        specific_ewma = specific_metric[
            "rolling_ewma"
        ]

        if specific_ewma is None:
            continue

        specific_weight = (
            sample_count
            / (
                sample_count
                + restaurant_prior_strength
            )
        )

        blended_wait = (
            specific_weight
            * float(
                specific_ewma
            )
            + (
                1.0
                - specific_weight
            )
            * global_restaurant_wait
        )

        restaurant_waits[
            key
        ] = {
            "restaurant_place_id":
                group[
                    "restaurant_place_id"
                ],

            "restaurant_name":
                group[
                    "restaurant_name"
                ],

            "samples":
                sample_count,

            "specific_ewma_minutes":
                float(
                    specific_ewma
                ),

            "specific_weight":
                specific_weight,

            "blended_wait_minutes":
                clamp(
                    blended_wait,
                    0.0,
                    60.0,
                ),
        }

    global_parameters = json.loads(
        json.dumps(
            BASELINE_ENGINE_PARAMETERS[
                "global"
            ]
        )
    )

    global_parameters.update(
        {
            "route_multiplier":
                route_multiplier,

            "route_fixed_delay_minutes":
                route_fixed_delay,

            "restaurant_wait_minutes":
                global_restaurant_wait,

            "customer_dropoff_minutes":
                global_dropoff,
        }
    )

    return {
        "status":
            "learned"
            if usable_offer_ids
            else "baseline",

        "source": {
            "eligible_orders":
                len(
                    orders
                ),

            "usable_orders":
                len(
                    usable_offer_ids
                ),

            "route_samples":
                route_sample_count,

            "restaurant_wait_samples":
                int(
                    restaurant_wait_metric[
                        "samples"
                    ]
                ),

            "dropoff_samples":
                int(
                    dropoff_metric[
                        "samples"
                    ]
                ),
        },

        "global":
            global_parameters,

        "restaurant_waits":
            restaurant_waits,

        "scoring":
            json.loads(
                json.dumps(
                    BASELINE_ENGINE_PARAMETERS[
                        "scoring"
                    ]
                )
            ),

        "travel_model":
            json.loads(
                json.dumps(
                    BASELINE_ENGINE_PARAMETERS[
                        "travel_model"
                    ]
                )
            ),

        "features":
            json.loads(
                json.dumps(
                    BASELINE_ENGINE_PARAMETERS[
                        "features"
                    ]
                )
            ),

        "learning": {
            "ewma_alpha":
                ewma_alpha,

            "minimum_global_samples":
                minimum_global_samples,

            "minimum_restaurant_samples":
                minimum_restaurant_samples,

            "restaurant_prior_strength":
                restaurant_prior_strength,
        },
    }


@app.get("/engine-config")
def engine_config() -> dict[str, Any]:
    """Return the latest persisted engine configuration."""
    return parse_engine_config(
        latest_engine_config_row()
    )


@app.get("/engine-config/history")
def engine_config_history(
    limit: int = Query(
        default=20,
        ge=1,
        le=100,
    ),
) -> dict[str, Any]:
    """Return recent engine-version summaries."""
    with get_connection() as connection:
        rows = connection.execute(
            """
            SELECT
                engine_version,
                generated_at,
                source_eligible_orders,
                source_usable_orders
            FROM engine_configs
            ORDER BY engine_version DESC
            LIMIT ?
            """,
            (
                limit,
            ),
        ).fetchall()

    return {
        "count":
            len(
                rows
            ),

        "versions": [
            {
                "engine_version":
                    row[
                        "engine_version"
                    ],

                "generated_at":
                    row[
                        "generated_at"
                    ],

                "source_eligible_orders":
                    row[
                        "source_eligible_orders"
                    ],

                "source_usable_orders":
                    row[
                        "source_usable_orders"
                    ],
            }
            for row in rows
        ],
    }


@app.post("/engine-config/generate")
def generate_engine_config(
    minimum_global_samples: int = Query(
        default=5,
        ge=2,
        le=10_000,
    ),
    minimum_restaurant_samples: int = Query(
        default=2,
        ge=1,
        le=10_000,
    ),
    ewma_alpha: float = Query(
        default=0.20,
        gt=0.0,
        le=1.0,
    ),
    restaurant_prior_strength: float = Query(
        default=10.0,
        gt=0.0,
        le=10_000.0,
    ),
) -> dict[str, Any]:
    """
    Build a candidate from eligible data and save a new version only when
    a scoring parameter has changed.
    """
    latest_row = latest_engine_config_row()

    latest_config = parse_engine_config(
        latest_row
    )

    candidate = build_engine_candidate(
        minimum_global_samples=
            minimum_global_samples,

        minimum_restaurant_samples=
            minimum_restaurant_samples,

        ewma_alpha=
            ewma_alpha,

        restaurant_prior_strength=
            restaurant_prior_strength,
    )

    candidate_signature = parameter_signature(
        candidate
    )

    latest_signature = parameter_signature(
        latest_config
    )

    if candidate_signature == latest_signature:
        return {
            "status": "unchanged",
            "engine_config": latest_config,
        }

    next_version = int(
        latest_row[
            "engine_version"
        ]
    ) + 1

    generated_at = datetime.now(
        timezone.utc
    ).isoformat()

    saved_config = {
        "engine_version":
            next_version,

        "generated_at":
            generated_at,

        **candidate,
    }

    source = saved_config[
        "source"
    ]

    with get_connection() as connection:
        connection.execute(
            """
            INSERT INTO engine_configs (
                engine_version,
                generated_at,
                source_eligible_orders,
                source_usable_orders,
                config_json
            )
            VALUES (?, ?, ?, ?, ?)
            """,
            (
                next_version,
                generated_at,
                int(
                    source[
                        "eligible_orders"
                    ]
                ),
                int(
                    source[
                        "usable_orders"
                    ]
                ),
                json.dumps(
                    saved_config,
                    ensure_ascii=False,
                    separators=(",", ":"),
                ),
            ),
        )
        connection.commit()

    return {
        "status": "created",
        "engine_config": saved_config,
    }


# ---------------------------------------------------------------------------
# DoorDash displayed-offer-wait calibration
# ---------------------------------------------------------------------------


def _wait_number(
    value: Any,
    name: str,
    minimum: float = 0.0,
    maximum: float = 90.0,
) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError):
        raise HTTPException(
            status_code=422,
            detail=f"{name} must be numeric.",
        )

    if not math.isfinite(number) or number < minimum or number > maximum:
        raise HTTPException(
            status_code=422,
            detail=f"{name} is outside its valid range.",
        )

    return number


def _offer_wait_calibration_for_range(
    minimum_minutes: float,
    maximum_minutes: float,
) -> dict[str, Any]:
    midpoint = (minimum_minutes + maximum_minutes) / 2.0

    with get_connection() as connection:
        exact_rows = connection.execute(
            """
            SELECT prediction_error_minutes, actual_wait_minutes
            FROM offer_wait_samples
            WHERE exclude_from_learning = 0
              AND ABS(range_min_minutes - ?) < 0.001
              AND ABS(range_max_minutes - ?) < 0.001
            """,
            (
                minimum_minutes,
                maximum_minutes,
            ),
        ).fetchall()

        global_rows = connection.execute(
            """
            SELECT prediction_error_minutes, actual_wait_minutes
            FROM offer_wait_samples
            WHERE exclude_from_learning = 0
            """
        ).fetchall()

    exact_errors = [
        float(row["prediction_error_minutes"])
        for row in exact_rows
    ]
    global_errors = [
        float(row["prediction_error_minutes"])
        for row in global_rows
    ]

    exact_count = len(exact_errors)
    global_count = len(global_errors)

    exact_mean_error = (
        statistics.fmean(exact_errors)
        if exact_errors
        else 0.0
    )
    global_mean_error = (
        statistics.fmean(global_errors)
        if global_errors
        else 0.0
    )

    # Global history is a deliberately weak prior. Exact-range history becomes
    # influential faster because it directly describes the live DoorDash range.
    global_confidence = global_count / (global_count + 20.0)
    exact_confidence = exact_count / (exact_count + 5.0)

    global_blended_correction = (
        global_mean_error * global_confidence
    )

    if exact_count > 0:
        adjustment = (
            exact_confidence * exact_mean_error
            + (1.0 - exact_confidence) * global_blended_correction
        )
        confidence = exact_confidence
    else:
        adjustment = global_blended_correction
        confidence = global_confidence

    adjustment = max(-10.0, min(10.0, adjustment))
    calibrated_wait = max(
        0.0,
        min(60.0, midpoint + adjustment),
    )

    exact_actual_mean = (
        statistics.fmean(
            float(row["actual_wait_minutes"])
            for row in exact_rows
        )
        if exact_rows
        else None
    )

    return {
        "range_min_minutes": minimum_minutes,
        "range_max_minutes": maximum_minutes,
        "range_midpoint_minutes": midpoint,
        "adjustment_minutes": adjustment,
        "calibrated_expected_wait_minutes": calibrated_wait,
        "range_sample_count": exact_count,
        "global_sample_count": global_count,
        "confidence": confidence,
        "range_mean_actual_wait_minutes": exact_actual_mean,
        "range_mean_prediction_error_minutes": (
            exact_mean_error if exact_count > 0 else None
        ),
        "global_mean_prediction_error_minutes": (
            global_mean_error if global_count > 0 else None
        ),
    }


@app.post("/offer-wait-samples")
def receive_offer_wait_sample(
    payload: dict[str, Any],
) -> dict[str, Any]:
    """Store one DoorDash-prediction versus actual-next-offer observation."""
    sample_id = payload.get("sample_id")
    if not isinstance(sample_id, str) or not sample_id.strip():
        raise HTTPException(
            status_code=422,
            detail="sample_id is required.",
        )

    minimum_minutes = _wait_number(
        payload.get("range_min_minutes"),
        "range_min_minutes",
        maximum=60.0,
    )
    maximum_minutes = _wait_number(
        payload.get("range_max_minutes"),
        "range_max_minutes",
        maximum=60.0,
    )
    if maximum_minutes < minimum_minutes:
        raise HTTPException(
            status_code=422,
            detail="range_max_minutes must be >= range_min_minutes.",
        )

    midpoint = (minimum_minutes + maximum_minutes) / 2.0

    started_at = payload.get("wait_started_at_wall_time_ms")
    next_offer_at = payload.get("next_offer_detected_at_wall_time_ms")
    actual_wait_ms = payload.get("actual_wait_ms")

    if not isinstance(started_at, int) or started_at <= 0:
        raise HTTPException(
            status_code=422,
            detail="wait_started_at_wall_time_ms must be a positive integer.",
        )
    if not isinstance(next_offer_at, int) or next_offer_at <= 0:
        raise HTTPException(
            status_code=422,
            detail="next_offer_detected_at_wall_time_ms must be a positive integer.",
        )
    if not isinstance(actual_wait_ms, int) or actual_wait_ms < 0:
        raise HTTPException(
            status_code=422,
            detail="actual_wait_ms must be a non-negative integer.",
        )

    actual_wait_minutes = actual_wait_ms / 60_000.0
    prediction_error_minutes = actual_wait_minutes - midpoint

    # Recompute rather than trusting derived values supplied by the phone.
    excluded = bool(payload.get("exclude_from_learning", False))
    exclusion_reason = payload.get("exclusion_reason")
    if exclusion_reason is not None and not isinstance(exclusion_reason, str):
        exclusion_reason = str(exclusion_reason)

    if next_offer_at <= started_at:
        excluded = True
        exclusion_reason = "INVALID_TIMESTAMPS"
    elif actual_wait_ms > 90 * 60 * 1000:
        excluded = True
        exclusion_reason = "WAIT_TOO_LONG"

    range_observed_at = payload.get("range_observed_at_wall_time_ms")
    if not isinstance(range_observed_at, int):
        range_observed_at = None

    local_hour = payload.get("local_hour")
    if not isinstance(local_hour, int) or local_hour not in range(24):
        local_hour = None

    day_of_week = payload.get("day_of_week")
    if not isinstance(day_of_week, int) or day_of_week not in range(1, 8):
        day_of_week = None

    received_at = datetime.now(timezone.utc).isoformat()
    raw_payload = dict(payload)
    raw_payload["range_midpoint_minutes"] = midpoint
    raw_payload["actual_wait_minutes"] = actual_wait_minutes
    raw_payload["prediction_error_minutes"] = prediction_error_minutes
    raw_payload["exclude_from_learning"] = excluded
    raw_payload["exclusion_reason"] = exclusion_reason
    raw_json = json.dumps(
        raw_payload,
        ensure_ascii=False,
        separators=(",", ":"),
    )

    try:
        with get_connection() as connection:
            connection.execute(
                """
                INSERT INTO offer_wait_samples (
                    sample_id,
                    next_offer_id,
                    range_min_minutes,
                    range_max_minutes,
                    range_midpoint_minutes,
                    range_observed_at_wall_time_ms,
                    wait_started_at_wall_time_ms,
                    next_offer_detected_at_wall_time_ms,
                    actual_wait_ms,
                    actual_wait_minutes,
                    prediction_error_minutes,
                    start_reason,
                    next_offer_detection_source,
                    local_hour,
                    day_of_week,
                    exclude_from_learning,
                    exclusion_reason,
                    received_at,
                    raw_json
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    sample_id,
                    payload.get("next_offer_id"),
                    minimum_minutes,
                    maximum_minutes,
                    midpoint,
                    range_observed_at,
                    started_at,
                    next_offer_at,
                    actual_wait_ms,
                    actual_wait_minutes,
                    prediction_error_minutes,
                    payload.get("start_reason"),
                    payload.get("next_offer_detection_source"),
                    local_hour,
                    day_of_week,
                    int(excluded),
                    exclusion_reason,
                    received_at,
                    raw_json,
                ),
            )
            connection.commit()
    except sqlite3.IntegrityError:
        return {
            "status": "already_exists",
            "sample_id": sample_id,
        }
    except sqlite3.Error as exc:
        raise HTTPException(
            status_code=500,
            detail=f"Database error: {exc}",
        ) from exc

    return {
        "status": "stored",
        "sample_id": sample_id,
        "exclude_from_learning": excluded,
        "prediction_error_minutes": prediction_error_minutes,
    }


@app.get("/offer-wait-calibration")
def offer_wait_calibration(
    min_minutes: float = Query(ge=0.0, le=60.0),
    max_minutes: float = Query(ge=0.0, le=60.0),
) -> dict[str, Any]:
    """Return the learned correction for DoorDash's current displayed range."""
    if max_minutes < min_minutes:
        raise HTTPException(
            status_code=422,
            detail="max_minutes must be >= min_minutes.",
        )

    return _offer_wait_calibration_for_range(
        minimum_minutes=float(min_minutes),
        maximum_minutes=float(max_minutes),
    )


@app.get("/offer-wait-samples/recent")
def recent_offer_wait_samples(
    limit: int = Query(default=50, ge=1, le=500),
) -> dict[str, Any]:
    with get_connection() as connection:
        rows = connection.execute(
            """
            SELECT *
            FROM offer_wait_samples
            ORDER BY next_offer_detected_at_wall_time_ms DESC
            LIMIT ?
            """,
            (limit,),
        ).fetchall()

    return {
        "count": len(rows),
        "samples": [dict(row) for row in rows],
    }


@app.get("/offer-wait-calibration/stats")
def offer_wait_calibration_stats() -> dict[str, Any]:
    """Show calibration quality for each DoorDash range observed so far."""
    with get_connection() as connection:
        rows = connection.execute(
            """
            SELECT
                range_min_minutes,
                range_max_minutes,
                COUNT(*) AS sample_count,
                AVG(actual_wait_minutes) AS mean_actual_wait_minutes,
                AVG(prediction_error_minutes) AS mean_prediction_error_minutes,
                MIN(actual_wait_minutes) AS minimum_actual_wait_minutes,
                MAX(actual_wait_minutes) AS maximum_actual_wait_minutes
            FROM offer_wait_samples
            WHERE exclude_from_learning = 0
            GROUP BY range_min_minutes, range_max_minutes
            ORDER BY range_min_minutes, range_max_minutes
            """
        ).fetchall()

    ranges: list[dict[str, Any]] = []
    for row in rows:
        calibration = _offer_wait_calibration_for_range(
            minimum_minutes=float(row["range_min_minutes"]),
            maximum_minutes=float(row["range_max_minutes"]),
        )
        ranges.append(
            {
                **dict(row),
                "adjustment_minutes": calibration["adjustment_minutes"],
                "calibrated_expected_wait_minutes": calibration[
                    "calibrated_expected_wait_minutes"
                ],
                "confidence": calibration["confidence"],
            }
        )

    with get_connection() as connection:
        total_row = connection.execute(
            """
            SELECT
                COUNT(*) AS total_samples,
                SUM(CASE WHEN exclude_from_learning = 0 THEN 1 ELSE 0 END)
                    AS learnable_samples
            FROM offer_wait_samples
            """
        ).fetchone()

    return {
        "range_count": len(ranges),
        "total_samples": int(total_row["total_samples"] or 0),
        "learnable_samples": int(total_row["learnable_samples"] or 0),
        "ranges": ranges,
    }


# ---------------------------------------------------------------------------
# Customer-map GPS calibration / learning
# ---------------------------------------------------------------------------


_CUSTOMER_MAP_EARTH_RADIUS_METERS = 6_371_008.8
_CUSTOMER_MAP_FEATURE_SCALE_METERS = 5_000.0
_CUSTOMER_MAP_MAX_CORRECTION_METERS = 500.0


def _customer_geo_number(
    value: Any,
    name: str,
    minimum: float,
    maximum: float,
) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError):
        raise HTTPException(
            status_code=422,
            detail=f"{name} must be numeric.",
        )

    if not math.isfinite(number) or number < minimum or number > maximum:
        raise HTTPException(
            status_code=422,
            detail=f"{name} is outside its valid range.",
        )
    return number


def _customer_optional_float(
    value: Any,
) -> float | None:
    if value is None:
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


def _customer_local_offset_meters(
    origin_latitude: float,
    origin_longitude: float,
    target_latitude: float,
    target_longitude: float,
) -> tuple[float, float]:
    """Return approximate east/north meters over DashTool's local delivery scale."""
    mean_lat = math.radians(
        (origin_latitude + target_latitude) / 2.0
    )
    east = (
        math.radians(target_longitude - origin_longitude)
        * _CUSTOMER_MAP_EARTH_RADIUS_METERS
        * math.cos(mean_lat)
    )
    north = (
        math.radians(target_latitude - origin_latitude)
        * _CUSTOMER_MAP_EARTH_RADIUS_METERS
    )
    return east, north


def _customer_apply_offset(
    latitude: float,
    longitude: float,
    east_meters: float,
    north_meters: float,
) -> tuple[float, float]:
    new_latitude = latitude + math.degrees(
        north_meters / _CUSTOMER_MAP_EARTH_RADIUS_METERS
    )
    cosine = max(
        1e-6,
        abs(math.cos(math.radians(latitude))),
    )
    new_longitude = longitude + math.degrees(
        east_meters
        / (_CUSTOMER_MAP_EARTH_RADIUS_METERS * cosine)
    )
    return new_latitude, new_longitude


def _customer_sample_weight(
    row: sqlite3.Row | dict[str, Any],
) -> float:
    def value(name: str, default: float) -> float:
        raw = row[name] if name in row.keys() else None
        try:
            number = float(raw)
        except (TypeError, ValueError):
            return default
        return number if math.isfinite(number) else default

    actual_accuracy = max(
        5.0,
        value("actual_accuracy_meters", 50.0),
    )
    driver_accuracy = max(
        5.0,
        value("driver_accuracy_meters", 50.0),
    )
    anchor_pixels = max(
        1.0,
        value("calibration_pixel_distance", 80.0),
    )
    extrapolation = max(
        1.0,
        value("extrapolation_ratio", 3.0),
    )

    accuracy_weight = 1.0 / (
        1.0
        + (actual_accuracy / 35.0) ** 2
        + (driver_accuracy / 45.0) ** 2
    )
    anchor_weight = max(
        0.25,
        min(1.0, anchor_pixels / 250.0),
    )
    extrapolation_weight = max(
        0.25,
        min(1.0, 3.0 / extrapolation),
    )
    return max(
        0.02,
        accuracy_weight * anchor_weight * extrapolation_weight,
    )


def _solve_linear_system(
    matrix: list[list[float]],
    vector: list[float],
) -> list[float] | None:
    """Small dependency-free Gaussian-elimination solver with pivoting."""
    n = len(vector)
    augmented = [
        [float(matrix[r][c]) for c in range(n)]
        + [float(vector[r])]
        for r in range(n)
    ]

    for column in range(n):
        pivot = max(
            range(column, n),
            key=lambda row_index: abs(augmented[row_index][column]),
        )
        if abs(augmented[pivot][column]) < 1e-10:
            return None
        if pivot != column:
            augmented[column], augmented[pivot] = (
                augmented[pivot],
                augmented[column],
            )

        divisor = augmented[column][column]
        for c in range(column, n + 1):
            augmented[column][c] /= divisor

        for row_index in range(n):
            if row_index == column:
                continue
            factor = augmented[row_index][column]
            if abs(factor) < 1e-14:
                continue
            for c in range(column, n + 1):
                augmented[row_index][c] -= (
                    factor * augmented[column][c]
                )

    return [
        augmented[index][n]
        for index in range(n)
    ]


def _customer_map_training_rows() -> list[sqlite3.Row]:
    with get_connection() as connection:
        return connection.execute(
            """
            SELECT *
            FROM customer_map_samples
            WHERE exclude_from_learning = 0
            ORDER BY received_at ASC
            """
        ).fetchall()


def _customer_feature_vector(
    raw_latitude: float,
    raw_longitude: float,
    driver_latitude: float,
    driver_longitude: float,
) -> list[float]:
    east, north = _customer_local_offset_meters(
        driver_latitude,
        driver_longitude,
        raw_latitude,
        raw_longitude,
    )
    return [
        1.0,
        east / _CUSTOMER_MAP_FEATURE_SCALE_METERS,
        north / _CUSTOMER_MAP_FEATURE_SCALE_METERS,
    ]


def _fit_customer_residual_model(
    rows: list[sqlite3.Row],
) -> dict[str, Any]:
    count = len(rows)
    if count == 0:
        return {
            "model_type": "RAW_ONLY",
            "sample_count": 0,
            "east_coefficients": [0.0, 0.0, 0.0],
            "north_coefficients": [0.0, 0.0, 0.0],
            "rmse_meters": None,
        }

    weights = [
        _customer_sample_weight(row)
        for row in rows
    ]
    total_weight = sum(weights)

    mean_east = sum(
        weight * float(row["raw_error_east_meters"])
        for weight, row in zip(weights, rows)
    ) / max(total_weight, 1e-9)
    mean_north = sum(
        weight * float(row["raw_error_north_meters"])
        for weight, row in zip(weights, rows)
    ) / max(total_weight, 1e-9)

    if count < 6:
        east_coefficients = [mean_east, 0.0, 0.0]
        north_coefficients = [mean_north, 0.0, 0.0]
        model_type = "WEIGHTED_BIAS"
    else:
        # Residual model: error = b0 + b1*(east/5km) + b2*(north/5km).
        # Ridge penalties keep directional terms near zero until data proves a
        # repeatable scale/rotation/directional error.
        dimension = 3
        normal = [
            [0.0 for _ in range(dimension)]
            for _ in range(dimension)
        ]
        east_rhs = [0.0 for _ in range(dimension)]
        north_rhs = [0.0 for _ in range(dimension)]

        for row, weight in zip(rows, weights):
            features = _customer_feature_vector(
                float(row["raw_latitude"]),
                float(row["raw_longitude"]),
                float(row["driver_latitude"]),
                float(row["driver_longitude"]),
            )
            error_east = float(row["raw_error_east_meters"])
            error_north = float(row["raw_error_north_meters"])

            for i in range(dimension):
                east_rhs[i] += weight * features[i] * error_east
                north_rhs[i] += weight * features[i] * error_north
                for j in range(dimension):
                    normal[i][j] += (
                        weight * features[i] * features[j]
                    )

        # Moderate intercept shrinkage; stronger shrinkage on directional terms.
        ridge = [2.0, 10.0, 10.0]
        for i, penalty in enumerate(ridge):
            normal[i][i] += penalty

        solved_east = _solve_linear_system(
            normal,
            east_rhs,
        )
        solved_north = _solve_linear_system(
            normal,
            north_rhs,
        )

        if solved_east is None or solved_north is None:
            east_coefficients = [mean_east, 0.0, 0.0]
            north_coefficients = [mean_north, 0.0, 0.0]
            model_type = "WEIGHTED_BIAS_FALLBACK"
        else:
            east_coefficients = solved_east
            north_coefficients = solved_north
            model_type = "RIDGE_DIRECTIONAL_RESIDUAL"

    squared_errors: list[float] = []
    for row in rows:
        features = _customer_feature_vector(
            float(row["raw_latitude"]),
            float(row["raw_longitude"]),
            float(row["driver_latitude"]),
            float(row["driver_longitude"]),
        )
        predicted_east = sum(
            coefficient * feature
            for coefficient, feature in zip(
                east_coefficients,
                features,
            )
        )
        predicted_north = sum(
            coefficient * feature
            for coefficient, feature in zip(
                north_coefficients,
                features,
            )
        )
        residual_east = (
            float(row["raw_error_east_meters"])
            - predicted_east
        )
        residual_north = (
            float(row["raw_error_north_meters"])
            - predicted_north
        )
        squared_errors.append(
            residual_east * residual_east
            + residual_north * residual_north
        )

    rmse = math.sqrt(
        statistics.fmean(squared_errors)
    ) if squared_errors else None

    return {
        "model_type": model_type,
        "sample_count": count,
        "east_coefficients": east_coefficients,
        "north_coefficients": north_coefficients,
        "rmse_meters": rmse,
    }


def _predict_customer_residual(
    model: dict[str, Any],
    raw_latitude: float,
    raw_longitude: float,
    driver_latitude: float,
    driver_longitude: float,
) -> tuple[float, float]:
    features = _customer_feature_vector(
        raw_latitude,
        raw_longitude,
        driver_latitude,
        driver_longitude,
    )
    east = sum(
        float(coefficient) * feature
        for coefficient, feature in zip(
            model["east_coefficients"],
            features,
        )
    )
    north = sum(
        float(coefficient) * feature
        for coefficient, feature in zip(
            model["north_coefficients"],
            features,
        )
    )
    return east, north


def _customer_map_calibration_for_prediction(
    raw_latitude: float,
    raw_longitude: float,
    driver_latitude: float,
    driver_longitude: float,
    restaurant_place_id: str | None,
) -> dict[str, Any]:
    rows = _customer_map_training_rows()
    model = _fit_customer_residual_model(rows)
    count = int(model["sample_count"])

    if count == 0:
        return {
            "raw_latitude": raw_latitude,
            "raw_longitude": raw_longitude,
            "corrected_latitude": raw_latitude,
            "corrected_longitude": raw_longitude,
            "correction_east_meters": 0.0,
            "correction_north_meters": 0.0,
            "sample_count": 0,
            "restaurant_sample_count": 0,
            "confidence": 0.0,
            "restaurant_confidence": 0.0,
            "model_type": "RAW_ONLY",
            "model_rmse_meters": None,
        }

    predicted_east, predicted_north = _predict_customer_residual(
        model,
        raw_latitude,
        raw_longitude,
        driver_latitude,
        driver_longitude,
    )

    # Sample-count confidence prevents early measurements from overcorrecting.
    global_confidence = count / (count + 8.0)

    # Noisy models should gain influence more slowly even with several samples.
    rmse = model.get("rmse_meters")
    if isinstance(rmse, (int, float)) and math.isfinite(float(rmse)):
        error_quality = max(
            0.35,
            min(1.0, 250.0 / max(250.0, float(rmse))),
        )
    else:
        error_quality = 1.0

    global_confidence *= error_quality
    correction_east = predicted_east * global_confidence
    correction_north = predicted_north * global_confidence

    restaurant_rows = [
        row
        for row in rows
        if restaurant_place_id
        and row["restaurant_place_id"] == restaurant_place_id
    ]
    restaurant_count = len(restaurant_rows)
    restaurant_confidence = 0.0

    if restaurant_count >= 3:
        residuals: list[tuple[float, float, float]] = []
        for row in restaurant_rows:
            global_east, global_north = _predict_customer_residual(
                model,
                float(row["raw_latitude"]),
                float(row["raw_longitude"]),
                float(row["driver_latitude"]),
                float(row["driver_longitude"]),
            )
            residuals.append(
                (
                    float(row["raw_error_east_meters"]) - global_east,
                    float(row["raw_error_north_meters"]) - global_north,
                    _customer_sample_weight(row),
                )
            )

        total_weight = sum(item[2] for item in residuals)
        restaurant_east = sum(
            east * weight
            for east, _, weight in residuals
        ) / max(total_weight, 1e-9)
        restaurant_north = sum(
            north * weight
            for _, north, weight in residuals
        ) / max(total_weight, 1e-9)

        restaurant_confidence = (
            restaurant_count / (restaurant_count + 5.0)
        )
        correction_east += (
            restaurant_east * restaurant_confidence
        )
        correction_north += (
            restaurant_north * restaurant_confidence
        )

    magnitude = math.hypot(
        correction_east,
        correction_north,
    )
    if magnitude > _CUSTOMER_MAP_MAX_CORRECTION_METERS:
        scale = _CUSTOMER_MAP_MAX_CORRECTION_METERS / magnitude
        correction_east *= scale
        correction_north *= scale

    corrected_latitude, corrected_longitude = _customer_apply_offset(
        raw_latitude,
        raw_longitude,
        correction_east,
        correction_north,
    )

    return {
        "raw_latitude": raw_latitude,
        "raw_longitude": raw_longitude,
        "corrected_latitude": corrected_latitude,
        "corrected_longitude": corrected_longitude,
        "correction_east_meters": correction_east,
        "correction_north_meters": correction_north,
        "sample_count": count,
        "restaurant_sample_count": restaurant_count,
        "confidence": global_confidence,
        "restaurant_confidence": restaurant_confidence,
        "model_type": model["model_type"],
        "model_rmse_meters": model["rmse_meters"],
    }


@app.post("/customer-map-samples")
def receive_customer_map_sample(
    payload: dict[str, Any],
) -> dict[str, Any]:
    """Store one predicted-customer versus confirmed-arrival GPS observation."""
    sample_id = payload.get("sample_id")
    if not isinstance(sample_id, str) or not sample_id.strip():
        raise HTTPException(
            status_code=422,
            detail="sample_id is required.",
        )

    raw_latitude = _customer_geo_number(
        payload.get("raw_latitude"),
        "raw_latitude",
        -90.0,
        90.0,
    )
    raw_longitude = _customer_geo_number(
        payload.get("raw_longitude"),
        "raw_longitude",
        -180.0,
        180.0,
    )
    actual_latitude = _customer_geo_number(
        payload.get("actual_latitude"),
        "actual_latitude",
        -90.0,
        90.0,
    )
    actual_longitude = _customer_geo_number(
        payload.get("actual_longitude"),
        "actual_longitude",
        -180.0,
        180.0,
    )
    driver_latitude = _customer_geo_number(
        payload.get("driver_latitude"),
        "driver_latitude",
        -90.0,
        90.0,
    )
    driver_longitude = _customer_geo_number(
        payload.get("driver_longitude"),
        "driver_longitude",
        -180.0,
        180.0,
    )
    restaurant_latitude = _customer_geo_number(
        payload.get("restaurant_latitude"),
        "restaurant_latitude",
        -90.0,
        90.0,
    )
    restaurant_longitude = _customer_geo_number(
        payload.get("restaurant_longitude"),
        "restaurant_longitude",
        -180.0,
        180.0,
    )

    error_east, error_north = _customer_local_offset_meters(
        raw_latitude,
        raw_longitude,
        actual_latitude,
        actual_longitude,
    )
    raw_error_meters = math.hypot(
        error_east,
        error_north,
    )

    actual_accuracy = _customer_optional_float(
        payload.get("actual_accuracy_meters")
    )
    driver_accuracy = _customer_optional_float(
        payload.get("driver_accuracy_meters")
    )
    driver_anchor_age = payload.get("driver_anchor_age_ms")
    if not isinstance(driver_anchor_age, int):
        driver_anchor_age = None
    anchor_pixels = _customer_optional_float(
        payload.get("calibration_pixel_distance")
    )
    customer_pixels = _customer_optional_float(
        payload.get("customer_pixel_distance")
    )
    extrapolation_ratio = _customer_optional_float(
        payload.get("extrapolation_ratio")
    )
    customer_house_score = _customer_optional_float(
        payload.get("customer_house_score")
    )

    reasons: list[str] = []
    supplied_reason = payload.get("exclusion_reason")
    if bool(payload.get("exclude_from_learning", False)):
        if isinstance(supplied_reason, str) and supplied_reason.strip():
            reasons.extend(
                part.strip()
                for part in supplied_reason.split(",")
                if part.strip()
            )
        else:
            reasons.append("PHONE_EXCLUDED")

    if actual_accuracy is None or actual_accuracy > 75.0:
        reasons.append("ACTUAL_GPS_LOW_ACCURACY")
    if driver_accuracy is None or driver_accuracy > 75.0:
        reasons.append("DRIVER_GPS_LOW_ACCURACY")
    if driver_anchor_age is None or abs(driver_anchor_age) > 15_000:
        reasons.append("DRIVER_GPS_TOO_FAR_FROM_SCREENSHOT")
    if anchor_pixels is None or anchor_pixels < 80.0:
        reasons.append("CALIBRATION_ANCHOR_TOO_SHORT")
    if extrapolation_ratio is None or extrapolation_ratio > 4.5:
        reasons.append("EXTREME_EXTRAPOLATION")
    if customer_house_score is not None and customer_house_score < 0.65:
        reasons.append("WEAK_CUSTOMER_PIN_CLASSIFICATION")
    if raw_error_meters > 1_500.0:
        reasons.append("RAW_ERROR_OUTLIER")

    actual_location_time = payload.get("actual_location_wall_time_ms")
    actual_requested_time = payload.get("actual_requested_at_wall_time_ms")
    if (
        isinstance(actual_location_time, int)
        and isinstance(actual_requested_time, int)
        and abs(actual_location_time - actual_requested_time) > 30_000
    ):
        reasons.append("ACTUAL_GPS_STALE")

    # Stable ordering and no duplicate reasons make diagnostics much easier.
    reasons = list(dict.fromkeys(reasons))
    excluded = bool(reasons)
    exclusion_reason = ",".join(reasons) if reasons else None

    received_at = datetime.now(timezone.utc).isoformat()
    raw_payload = dict(payload)
    raw_payload["raw_error_east_meters"] = error_east
    raw_payload["raw_error_north_meters"] = error_north
    raw_payload["raw_error_meters"] = raw_error_meters
    raw_payload["exclude_from_learning"] = excluded
    raw_payload["exclusion_reason"] = exclusion_reason
    raw_json = json.dumps(
        raw_payload,
        ensure_ascii=False,
        separators=(",", ":"),
    )

    columns = [
        "sample_id",
        "offer_id",
        "prediction_id",
        "restaurant_place_id",
        "predicted_at_wall_time_ms",
        "screenshot_captured_at_wall_time_ms",
        "raw_latitude",
        "raw_longitude",
        "corrected_latitude_at_prediction",
        "corrected_longitude_at_prediction",
        "calibration_sample_count_at_prediction",
        "calibration_confidence_at_prediction",
        "calibration_model_type_at_prediction",
        "actual_latitude",
        "actual_longitude",
        "actual_accuracy_meters",
        "actual_location_wall_time_ms",
        "actual_requested_at_wall_time_ms",
        "actual_location_source",
        "delivery_confirmed_at_wall_time_ms",
        "confirmation_source",
        "driver_latitude",
        "driver_longitude",
        "driver_accuracy_meters",
        "driver_location_wall_time_ms",
        "driver_anchor_age_ms",
        "restaurant_latitude",
        "restaurant_longitude",
        "driver_pixel_x",
        "driver_pixel_y",
        "restaurant_pixel_x",
        "restaurant_pixel_y",
        "customer_pixel_x",
        "customer_pixel_y",
        "restaurant_white_density",
        "restaurant_house_score",
        "customer_white_density",
        "customer_house_score",
        "calibration_pixel_distance",
        "customer_pixel_distance",
        "extrapolation_ratio",
        "anchor_straight_line_meters",
        "approximate_meters_per_pixel",
        "raw_error_east_meters",
        "raw_error_north_meters",
        "raw_error_meters",
        "exclude_from_learning",
        "exclusion_reason",
        "received_at",
        "raw_json",
    ]

    values = [
        sample_id,
        payload.get("offer_id"),
        payload.get("prediction_id"),
        payload.get("restaurant_place_id"),
        payload.get("predicted_at_wall_time_ms"),
        payload.get("screenshot_captured_at_wall_time_ms"),
        raw_latitude,
        raw_longitude,
        _customer_optional_float(payload.get("corrected_latitude")),
        _customer_optional_float(payload.get("corrected_longitude")),
        payload.get("calibration_sample_count"),
        _customer_optional_float(payload.get("calibration_confidence")),
        payload.get("calibration_model_type"),
        actual_latitude,
        actual_longitude,
        actual_accuracy,
        payload.get("actual_location_wall_time_ms"),
        payload.get("actual_requested_at_wall_time_ms"),
        payload.get("actual_location_source"),
        payload.get("delivery_confirmed_at_wall_time_ms"),
        payload.get("confirmation_source"),
        driver_latitude,
        driver_longitude,
        driver_accuracy,
        payload.get("driver_location_wall_time_ms"),
        driver_anchor_age,
        restaurant_latitude,
        restaurant_longitude,
        payload.get("driver_pixel_x"),
        payload.get("driver_pixel_y"),
        payload.get("restaurant_pixel_x"),
        payload.get("restaurant_pixel_y"),
        payload.get("customer_pixel_x"),
        payload.get("customer_pixel_y"),
        _customer_optional_float(payload.get("restaurant_white_density")),
        _customer_optional_float(payload.get("restaurant_house_score")),
        _customer_optional_float(payload.get("customer_white_density")),
        customer_house_score,
        anchor_pixels,
        customer_pixels,
        extrapolation_ratio,
        _customer_optional_float(payload.get("anchor_straight_line_meters")),
        _customer_optional_float(payload.get("approximate_meters_per_pixel")),
        error_east,
        error_north,
        raw_error_meters,
        int(excluded),
        exclusion_reason,
        received_at,
        raw_json,
    ]

    placeholders = ",".join("?" for _ in columns)
    try:
        with get_connection() as connection:
            connection.execute(
                f"""
                INSERT INTO customer_map_samples (
                    {','.join(columns)}
                )
                VALUES ({placeholders})
                """,
                values,
            )
            connection.commit()
    except sqlite3.IntegrityError:
        return {
            "status": "already_exists",
            "sample_id": sample_id,
        }
    except sqlite3.Error as exc:
        raise HTTPException(
            status_code=500,
            detail=f"Database error: {exc}",
        ) from exc

    return {
        "status": "stored",
        "sample_id": sample_id,
        "raw_error_meters": raw_error_meters,
        "raw_error_east_meters": error_east,
        "raw_error_north_meters": error_north,
        "exclude_from_learning": excluded,
        "exclusion_reason": exclusion_reason,
    }


@app.get("/customer-map/calibration")
def customer_map_calibration(
    raw_latitude: float = Query(ge=-90.0, le=90.0),
    raw_longitude: float = Query(ge=-180.0, le=180.0),
    driver_latitude: float = Query(ge=-90.0, le=90.0),
    driver_longitude: float = Query(ge=-180.0, le=180.0),
    restaurant_place_id: str | None = Query(default=None),
) -> dict[str, Any]:
    """Return the confidence-weighted learned residual correction."""
    return _customer_map_calibration_for_prediction(
        raw_latitude=float(raw_latitude),
        raw_longitude=float(raw_longitude),
        driver_latitude=float(driver_latitude),
        driver_longitude=float(driver_longitude),
        restaurant_place_id=(
            restaurant_place_id.strip()
            if isinstance(restaurant_place_id, str)
            and restaurant_place_id.strip()
            else None
        ),
    )


@app.get("/customer-map-samples/recent")
def recent_customer_map_samples(
    limit: int = Query(default=50, ge=1, le=500),
) -> dict[str, Any]:
    with get_connection() as connection:
        rows = connection.execute(
            """
            SELECT *
            FROM customer_map_samples
            ORDER BY received_at DESC
            LIMIT ?
            """,
            (limit,),
        ).fetchall()

    return {
        "count": len(rows),
        "samples": [dict(row) for row in rows],
    }


@app.get("/customer-map-calibration/stats")
def customer_map_calibration_stats() -> dict[str, Any]:
    with get_connection() as connection:
        all_rows = connection.execute(
            """
            SELECT *
            FROM customer_map_samples
            ORDER BY received_at ASC
            """
        ).fetchall()

    eligible = [
        row
        for row in all_rows
        if int(row["exclude_from_learning"] or 0) == 0
    ]
    raw_errors = [
        float(row["raw_error_meters"])
        for row in eligible
    ]
    model = _fit_customer_residual_model(eligible)

    restaurant_counts: dict[str, int] = {}
    for row in eligible:
        place_id = row["restaurant_place_id"]
        if isinstance(place_id, str) and place_id:
            restaurant_counts[place_id] = (
                restaurant_counts.get(place_id, 0) + 1
            )

    return {
        "total_samples": len(all_rows),
        "learnable_samples": len(eligible),
        "excluded_samples": len(all_rows) - len(eligible),
        "model_type": model["model_type"],
        "model_sample_count": model["sample_count"],
        "model_rmse_meters": model["rmse_meters"],
        "mean_raw_error_meters": (
            statistics.fmean(raw_errors)
            if raw_errors
            else None
        ),
        "median_raw_error_meters": (
            statistics.median(raw_errors)
            if raw_errors
            else None
        ),
        "minimum_raw_error_meters": (
            min(raw_errors)
            if raw_errors
            else None
        ),
        "maximum_raw_error_meters": (
            max(raw_errors)
            if raw_errors
            else None
        ),
        "restaurants_with_learning_data": len(restaurant_counts),
        "restaurant_sample_counts": restaurant_counts,
        "current_global_confidence": (
            len(eligible) / (len(eligible) + 8.0)
            if eligible
            else 0.0
        ),
    }
