# DashTool

DashTool is an Android driver-assistance prototype designed to evaluate DoorDash delivery offers using estimated earnings, travel time, historical wait data, and delivery lifecycle information.

The project combines an Android application with a Python/FastAPI backend to collect delivery data, estimate order profitability, learn from completed deliveries, and provide real-time information through a floating overlay.

> **Note:** DashTool is an independent personal engineering project and is not affiliated with or endorsed by DoorDash.

## Overview

Delivery offers can be difficult to evaluate quickly because the displayed payout and mileage do not capture the full amount of time an order may require.

DashTool attempts to estimate the actual value of an offer by considering factors such as:

- offered payout
- delivery mileage
- estimated travel time
- restaurant wait time
- customer drop-off time
- fuel-adjusted profitability
- historical delivery data
- expected time before the next offer

The result is displayed through a floating Android overlay so the information can be viewed without leaving the delivery app.

## Features

### Real-Time Offer Evaluation

DashTool detects delivery-offer information and calculates useful metrics including:

- projected earnings per hour
- estimated completion time
- delivery profitability
- an overall order score

### Floating Android Overlay

A draggable overlay provides a compact view of the current offer.

The expanded interface can display:

- restaurant
- order score
- projected order $/hr
- estimated completion time
- waiting-area information
- destination information

The overlay can also collapse into a smaller view while driving.

### Delivery Lifecycle Tracking

DashTool tracks the progression of a delivery through stages such as:

1. offer accepted
2. driving to restaurant
3. restaurant arrival
4. pickup
5. driving to customer
6. customer arrival
7. delivery completion

Lifecycle timing allows estimated delivery times to be compared with actual results.

### Accessibility and OCR Detection

The Android application combines Android accessibility services with on-device text recognition to detect relevant information from the delivery interface.

Notification monitoring can also trigger offer detection when a new delivery notification arrives.

### Location and Route Data

DashTool uses device location and route information to improve travel-time estimates and record where important delivery events occur.

The project also contains a customer-map coordinate estimation system that can compare predicted destination coordinates with actual delivery locations and collect calibration data.

### Historical Learning Engine

Completed-order data can be sent to the DashTool server, where historical observations are used to improve future estimates.

The learning system includes support for:

- restaurant-specific wait estimates
- customer travel-time modeling
- route-time correction
- customer drop-off timing
- outlier-resistant parameter learning
- sparse-data protection
- versioned engine configurations

The system is designed to avoid aggressively changing estimates when only a small number of observations are available.

### Waiting-Area Analysis

After a delivery, DashTool can evaluate potential waiting areas rather than treating every idle location equally.

The system stores information about:

- restaurant locations
- candidate waiting centers
- travel time to waiting areas
- time spent waiting for the next offer
- whether an offer arrived before reaching the recommended area

This creates a dataset that can be used to improve future waiting-area recommendations.

## Architecture

```text
DoorDash Interface
       |
       v
Android Accessibility / OCR / Notifications
       |
       v
DashTool Android App
       |
       +---- Offer Evaluation
       |
       +---- Lifecycle Tracking
       |
       +---- Location / Route Processing
       |
       +---- Floating Overlay
       |
       v
Python FastAPI Server
       |
       +---- SQLite Data Storage
       |
       +---- Historical Learning
       |
       +---- Restaurant / Waiting-Area Data
       |
       +---- Engine Configuration
       |
       v
Updated Estimates
```

## Technology Stack

### Android

- Kotlin
- Android SDK
- Jetpack Compose
- Android AccessibilityService
- NotificationListenerService
- Google ML Kit Text Recognition
- Google Play Services Location
- Google Places API
- Room
- Kotlin Coroutines
- Gradle

### Backend

- Python
- FastAPI
- SQLite
- Pydantic
- Python unittest

### Data and Modeling

The project uses several approaches to make learned parameters more stable, including:

- recency weighting
- robust statistics
- outlier resistance
- weighted estimates
- minimum sample thresholds
- parameter shrinkage
- monotonic travel-time fitting
- validation before applying learned route corrections

## Repository Structure

DashTool/
- android/ — Android application
- DashToolServer/
  - main.py
  - engine_learning_v2.py
  - test_engine_learning_v2.py
- .gitignore
- README.md

## Testing

The server learning engine includes unit tests covering behavior such as:

- preventing overfitting with small datasets
- maintaining realistic travel-time relationships
- resisting extreme restaurant-wait outliers
- preserving the configuration schema expected by the Android application

The Android application has also been iteratively tested using simulated screens and real delivery lifecycle data.

## Engineering Challenges

Some of the main engineering challenges addressed during development include:

- extracting useful information from a changing third-party application interface
- maintaining a reliable delivery state machine
- preventing missed lifecycle events from corrupting later data
- combining predicted route information with observed delivery times
- learning from limited real-world data without overfitting
- synchronizing an Android client with a local backend
- estimating useful waiting locations after deliveries
- protecting API credentials and private delivery/location data

## Privacy and Repository Data

API credentials, generated databases, exported delivery records, build artifacts, and locally collected location data are intentionally excluded from this repository.

The repository contains the application and learning-system source code rather than the private dataset collected during testing.

## Project Status

DashTool is a personal engineering and portfolio project developed for experimentation and real-world testing.

The current version demonstrates the system architecture from offer detection and Android UI through lifecycle data collection, backend storage, and adaptive parameter learning.

It is not intended as a production or commercial release.
