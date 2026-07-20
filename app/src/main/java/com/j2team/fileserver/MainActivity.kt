package com.j2team.fileserver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.j2team.fileserver.core.ui.FileServerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FileServerAppShell()
        }
    }
}

@Composable
private fun FileServerAppShell() {
    FileServerTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                Surface(tonalElevation = 1.dp) {
                    Text(
                        text = stringResource(R.string.app_name),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                        style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                    )
                }
            },
        ) { contentPadding ->
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {}
        }
    }
}
