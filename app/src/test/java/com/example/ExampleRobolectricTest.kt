package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `app metadata and permissions support Android 12 through 16`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Hindi Movie Dubbing AI", appName)

    @Suppress("DEPRECATION")
    val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      context.packageManager.getPackageInfo(
        context.packageName,
        PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
      )
    } else {
      context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
    }
    val permissions = pkgInfo.requestedPermissions?.toSet().orEmpty()
    assertTrue(permissions.contains(Manifest.permission.FOREGROUND_SERVICE))
    assertTrue(permissions.contains(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC))
    assertTrue(permissions.contains(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING))
    assertTrue(permissions.contains(Manifest.permission.POST_NOTIFICATIONS))
  }
}
