package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Business Manager", appName)
  }

  @Test
  fun `verify index html asset contains negative stock and low stock warning logic`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val assetContent = context.assets.open("index.html").bufferedReader().use { it.readText() }

    // Verify negative stock support in stk calculation
    org.junit.Assert.assertTrue(
      assetContent.contains("return (it.open||0)+sum(S.purch.flatMap(d=>d.lines).filter(l=>l.iid==it.id),l=>l.q)-sold(it)-woQty(it)")
    )

    // Verify exact warning message
    org.junit.Assert.assertTrue(
      assetContent.contains("Are you sure you want to make an order for this item? Because item quantity is going below stock level.")
    )

    // Verify Yes / Continue and No / Cancel options
    org.junit.Assert.assertTrue(assetContent.contains("Yes / Continue"))
    org.junit.Assert.assertTrue(assetContent.contains("No / Cancel"))
  }
}
