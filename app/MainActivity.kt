package com.nurasql77.sorr

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.view.ViewGroup
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.LibretroDroid

class MainActivity : Activity() {

    private lateinit var retroView: GLRetroView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL

        val title = TextView(this)
        title.text = "Streets of Rage Remake"
        title.textSize = 20f
        title.setPadding(20, 20, 20, 20)

        val start = Button(this)
        start.text = "Запустить SoRR"

        root.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            start,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(root)

        start.setOnClickListener {
            startGame()
        }
    }

    private fun startGame() {
        val core = "$filesDir/bennugd_libretro.so"
        val game = "$filesDir/sorr/SorR.dat"

        retroView = GLRetroView(
            this,
            core,
            game,
            "$filesDir/sorr",
            "",
            LibretroDroid.SHADER_NONE
        )

        setContentView(retroView)
    }

    override fun onDestroy() {
        if (::retroView.isInitialized) {
            retroView.close()
        }

        super.onDestroy()
    }
}
