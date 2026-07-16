// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.coletz.opencfmoto

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Lists saved rides (most recent first). Tap a card to see its route on the map; long-press to delete. */
class TripsListActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_trips)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.trips_root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        container = findViewById(R.id.trips_container)
        empty = findViewById(R.id.trips_empty)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        container.removeAllViews()
        val trips = TripStore.list(this)
        empty.visibility = if (trips.isEmpty()) View.VISIBLE else View.GONE
        for (trip in trips) container.addView(buildCard(trip))
    }

    private fun buildCard(trip: Trip): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            radius = dp(18).toFloat()
            setCardBackgroundColor(ContextCompat.getColor(this@TripsListActivity, R.color.surface))
            setContentPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            setOnClickListener { TripMapActivity.start(this@TripsListActivity, trip.id) }
            setOnLongClickListener { confirmDelete(trip); true }
        }

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        col.addView(TextView(this).apply {
            text = trip.dateText()
            setTextColor(ContextCompat.getColor(this@TripsListActivity, R.color.text_primary))
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = trip.timeRangeText()
            setTextColor(ContextCompat.getColor(this@TripsListActivity, R.color.text_secondary))
            textSize = 13f
        })

        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        stats.addView(stat("Distance", trip.distanceText()))
        stats.addView(stat("Time", trip.durationText()))
        stats.addView(stat("Avg", "${trip.avgKmh} km/h"))
        stats.addView(stat("Max", "${trip.maxKmh} km/h"))
        col.addView(stats)

        card.addView(col)
        return card
    }

    private fun stat(label: String, value: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        box.addView(TextView(this).apply {
            text = label.uppercase()
            setTextColor(ContextCompat.getColor(this@TripsListActivity, R.color.text_secondary))
            textSize = 10f
            gravity = Gravity.START
        })
        box.addView(TextView(this).apply {
            text = value
            setTextColor(ContextCompat.getColor(this@TripsListActivity, R.color.text_primary))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        return box
    }

    private fun confirmDelete(trip: Trip) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete trip?")
            .setMessage("${trip.dateText()} · ${trip.distanceText()}")
            .setPositiveButton("Delete") { _, _ ->
                TripStore.delete(this, trip.id)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun start(ctx: Context) {
            ctx.startActivity(Intent(ctx, TripsListActivity::class.java))
        }
    }
}
