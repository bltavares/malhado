package com.bltavares.malhado

import android.os.Bundle
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bltavares.malhado.ui.theme.MalhadoTheme

class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MalhadoTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        ExplainHealthConnectUsage(
                            modifier = Modifier.padding(
                                PaddingValues(
                                    horizontal = 20.dp, vertical = 15.dp
                                )
                            )
                        )
                    }
                }
            }
        }
    }
}


@Composable
fun ExplainHealthConnectUsage(modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text = "Welcome to Malhado.", style = MaterialTheme.typography.titleLarge)
        Text(text = "This app is used to import \".fit\" files into Health Connect to be shared with other apps.")
        Text(text = "It will require write permissions to be able to insert data into Health Connect.")
        Text(text = "No data is stored anywhere else.")
    }

}

@Preview(showBackground = true)
@Composable
fun ExplainHealthConnectUsagePreview() {
    MalhadoTheme {
        ExplainHealthConnectUsage()
    }
}