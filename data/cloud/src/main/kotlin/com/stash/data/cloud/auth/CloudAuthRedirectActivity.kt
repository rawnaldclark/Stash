package com.stash.data.cloud.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Receives the browser's redirect at the end of a cloud sign-in (`<applicationId>://oauth/onedrive`,
 * `db-<app key>://1/connect`), hands it to [CloudAuthCoordinator] and brings Stash back to the
 * front, closing the Custom Tab above it. No UI of its own.
 *
 * Exported because a browser must be able to open it. Anything can send it a URI, so it does
 * nothing with one unless the coordinator recognises it AND it carries the state of a sign-in
 * this phone started (checked in the coordinator).
 */
class CloudAuthRedirectActivity : Activity() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun coordinator(): CloudAuthCoordinator
    }

    private val coordinator: CloudAuthCoordinator by lazy {
        EntryPointAccessors.fromApplication(applicationContext, Deps::class.java).coordinator()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
        finish()
    }

    private fun handle(intent: Intent?) {
        val uri = intent?.dataString ?: return
        if (!coordinator.isRedirect(uri)) return
        coordinator.handleRedirect(uri)
        // Back to the screen that started the sign-in, popping the Custom Tab on top of it.
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(launch)
        }
    }
}
