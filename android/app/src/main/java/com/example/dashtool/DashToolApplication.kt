package com.example.dashtool

import android.app.Application
import android.util.Log
import com.google.android.libraries.places.api.Places

class DashToolApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val apiKey = BuildConfig.PLACES_API_KEY

        if (
            apiKey.isBlank() ||
            apiKey == "DEFAULT_API_KEY"
        ) {
            Log.e(
                "DashToolPlaces",
                "Places API key is missing."
            )
            return
        }

        if (!Places.isInitialized()) {
            Places.initializeWithNewPlacesApiEnabled(
                applicationContext,
                apiKey
            )
        }

        Log.d(
            "DashToolPlaces",
            "Places SDK initialized successfully."
        )
    }
}