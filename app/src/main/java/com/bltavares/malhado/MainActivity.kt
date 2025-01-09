package com.bltavares.malhado

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SpeedRecord
import com.bltavares.malhado.ui.theme.MalhadoTheme

sealed class ApplicationState {
    data object Missing : ApplicationState()
    data object Checking : ApplicationState()
    data object RequiresPermission : ApplicationState()
    data class AllPermissions(val client: HealthConnectClient) : ApplicationState()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MalhadoTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        val providedGrants = remember { mutableStateOf<Set<String>?>(null) }
                        val grantLauncher =
                            rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
                                providedGrants.value = granted
                            }

                        val state by produceState<ApplicationState>(
                            initialValue = ApplicationState.Checking, providedGrants.value
                        ) {
                            val client = healthConnectClient(applicationContext)
                            if (client == null) {
                                value = ApplicationState.Missing
                                return@produceState
                            }

                            val granted = client.permissionController.getGrantedPermissions()
                            if (granted.containsAll(REQUIRED_PERMISSIONS)) {
                                value = ApplicationState.AllPermissions(client)
                                return@produceState
                            }
                            value = ApplicationState.RequiresPermission
                        }

                        when (state) {
                            is ApplicationState.Checking -> MalhadoTitle()
                            is ApplicationState.Missing -> NoHealthConnect()
                            is ApplicationState.RequiresPermission -> TextButton(onClick = {
                                grantLauncher.launch(REQUIRED_PERMISSIONS)
                            }) {
                                Text("Permitir")
                            }

                            is ApplicationState.AllPermissions -> Text("Ready")
                        }
                    }
                }

            }


        }
    }


    private fun healthConnectClient(context: Context): HealthConnectClient? {
        val availabilityStatus = HealthConnectClient.getSdkStatus(context)
        if (availabilityStatus == HealthConnectClient.SDK_UNAVAILABLE) {
            return null
        }
        if (availabilityStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
            val uriString =
                "market://details?id=${DEFAULT_PROVIDER_PACKAGE_NAME}&url=healthconnect%3A%2F%2Fonboarding"
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setPackage("com.android.vending")
                data = Uri.parse(uriString)
                putExtra("overlay", true)
                putExtra("callerId", context.packageName)
            })
            return null
        }
        return HealthConnectClient.getOrCreate(context)
    }


    companion object {
        const val DEFAULT_PROVIDER_PACKAGE_NAME = "com.google.android.apps.healthdata"
        val REQUIRED_PERMISSIONS = setOf(
            HealthPermission.getWritePermission(ExerciseSessionRecord::class),
            HealthPermission.getWritePermission(HeartRateRecord::class),
            HealthPermission.getWritePermission(SpeedRecord::class),
            HealthPermission.getWritePermission(DistanceRecord::class),
            HealthPermission.getWritePermission(CyclingPedalingCadenceRecord::class),
        )
    }
}


@Composable
private fun MalhadoTitle() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(space = 10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Malhado.", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Import .fit files into Health Connect", style = MaterialTheme.typography.labelLarge
        )
    }
}


@Composable
fun NoHealthConnect() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(space = 30.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MalhadoTitle()
        Text("❌ No Health Connect found in the device.")
    }

}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun NoHealthConnectPreview() {
    NoHealthConnect()
}
