package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ui.MainNavGraph
import com.example.ui.theme.RigScriptTheme
import com.example.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RigScriptTheme {
                MainNavGraph(vm)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Persist active project whenever the app leaves foreground
        vm.saveActiveProject()
    }
}
