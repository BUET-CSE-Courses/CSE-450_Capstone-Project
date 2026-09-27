package com.example.capstone

import android.app.Application
import com.example.capstone.di.AppContainer
import com.example.capstone.di.DefaultAppContainer

class CapstoneApplication : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
    }
}
