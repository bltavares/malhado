package com.bltavares.malhado

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.health.connect.datatypes.ExerciseSessionType
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.permission.HealthPermission.Companion.PERMISSION_WRITE_EXERCISE_ROUTE
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseRoute.Location
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Power
import androidx.health.connect.client.units.Velocity
import androidx.health.connect.client.units.kilocalories
import androidx.health.connect.client.units.meters
import androidx.health.connect.client.units.watts
import com.bltavares.malhado.MainActivity.Companion.METADATA
import com.bltavares.malhado.ui.theme.MalhadoTheme
import com.garmin.fit.FitDecoder
import com.garmin.fit.Sport
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

sealed class ApplicationState {
    data object Missing : ApplicationState()
    data object Checking : ApplicationState()
    data object RequiresPermission : ApplicationState()
    data object AllPermissions : ApplicationState()
    data class FitFileSelected(val content: Uri) : ApplicationState()
    data class FitFileLoaded(val fitMessage: ParsedResponse) : ApplicationState()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val receivedShareFile = intent?.let {
            it.data ?: it.getParcelableExtra(Intent.EXTRA_STREAM, Parcelable::class.java) as? Uri
        }

        setContent {
            val scope = rememberCoroutineScope()
            val snackbarHostState = remember { SnackbarHostState() }

            MalhadoTheme {
                Scaffold(modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(hostState = snackbarHostState) }) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        var state by remember { mutableStateOf<ApplicationState>(ApplicationState.Checking) }

                        val grantLauncher =
                            rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
                                if (granted.containsAll(REQUIRED_PERMISSIONS)) {
                                    state =
                                        receivedShareFile?.let(ApplicationState::FitFileSelected)
                                            ?: ApplicationState.AllPermissions
                                }
                            }

                        val openFitFileLauncher =
                            rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { fileContent ->
                                fileContent?.let {
                                    state = ApplicationState.FitFileSelected(it)
                                }
                            }

                        LaunchedEffect(true) {
                            val client = healthConnectClient(applicationContext)
                            if (client == null) {
                                state = ApplicationState.Missing
                                return@LaunchedEffect
                            }

                            val granted = client.permissionController.getGrantedPermissions()
                            if (granted.containsAll(REQUIRED_PERMISSIONS)) {
                                state = receivedShareFile?.let(ApplicationState::FitFileSelected)
                                    ?: ApplicationState.AllPermissions
                                return@LaunchedEffect
                            }
                            state = ApplicationState.RequiresPermission
                        }


                        state.let {
                            when (it) {
                                is ApplicationState.Checking -> SplashScreen()
                                is ApplicationState.Missing -> NoHealthConnectScreen()
                                is ApplicationState.RequiresPermission -> PermissionRequiredScreen {
                                    grantLauncher.launch(REQUIRED_PERMISSIONS)
                                }

                                is ApplicationState.AllPermissions -> ConnectedScreen {
                                    openFitFileLauncher.launch("*/*")
                                }

                                is ApplicationState.FitFileSelected -> LoadingFitFileScreen(
                                    readContext = applicationContext.contentResolver to it.content,
                                    { exercise ->
                                        if (exercise != null) {
                                            state = ApplicationState.FitFileLoaded(exercise)
                                            return@LoadingFitFileScreen
                                        }

                                        state = ApplicationState.AllPermissions
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                "Failed to parse .fit file content",
                                                duration = SnackbarDuration.Short,
                                            )
                                        }
                                    },
                                )

                                is ApplicationState.FitFileLoaded -> FitFilePreviewScreen(
                                    it.fitMessage,
                                    onBack = {
                                        state = ApplicationState.AllPermissions
                                    },
                                    onSend = {
                                        val client = healthConnectClient(applicationContext)
                                        if (client == null) {
                                            scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    "❌ Failed to connect to Health Connect",
                                                    duration = SnackbarDuration.Long,
                                                )
                                            }
                                            return@FitFilePreviewScreen
                                        }

                                        client.insertRecords(
                                            listOfNotNull(
                                                it.fitMessage.session,
                                                it.fitMessage.distance,
                                                it.fitMessage.calories,
                                                it.fitMessage.heartRate,
                                                it.fitMessage.speed,
                                                it.fitMessage.power,
                                                it.fitMessage.cadence,
                                            )
                                        )
                                        state = ApplicationState.AllPermissions
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                "✅ .fit file imported in Health Connect",
                                                duration = SnackbarDuration.Long,
                                            )
                                        }
                                    },
                                )
                            }
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
            HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getWritePermission(PowerRecord::class),
            PERMISSION_WRITE_EXERCISE_ROUTE,
        )
        val METADATA = Metadata(
            dataOrigin = DataOrigin("com.bltavares.malhado"),
            recordingMethod = Metadata.RECORDING_METHOD_ACTIVELY_RECORDED,
        )
    }
}

@Composable
private fun PermissionRequiredScreen(onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(space = 10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MalhadoTitle()
        Spacer(modifier = Modifier.height(10.dp))
        FilledTonalButton(
            onClick = onClick,
            modifier = Modifier.padding(ButtonDefaults.TextButtonWithIconContentPadding),
        ) {
            Icon(
                painterResource(R.drawable.ic_health_connect_logo),
                "Health Connect logo",
                modifier = Modifier.size(ButtonDefaults.IconSize)
            )
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text("Connect to Health Connect")
        }
    }
}

@Preview(showSystemUi = true, showBackground = true)
@Composable
private fun PermissionRequireScreenPreview() {
    PermissionRequiredScreen {}
}

@Composable
fun SplashScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(space = 10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MalhadoTitle()
        LinearProgressIndicator()
    }

}

@Preview(showSystemUi = true, showBackground = true)
@Composable
private fun SplashScreenPreview() {
    SplashScreen()
}

@Composable
private fun MalhadoTitle() {
    Column(
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
fun NoHealthConnectScreen() {
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
    NoHealthConnectScreen()
}


@Composable
fun ConnectedScreen(onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(space = 30.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MalhadoTitle()
        ElevatedButton(
            onClick = onClick,
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        ) {
            Icon(
                imageVector = Icons.Filled.AddCircle,
                contentDescription = "Add icon",
                modifier = Modifier.size(ButtonDefaults.IconSize)
            )
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text("Load .fit file")
        }
    }
}

@Preview(showSystemUi = true, showBackground = true)
@Composable
fun ConnectedScreenPreview() {
    ConnectedScreen {}
}


private const val FIT_GPS_COORD_SYSTEM = 11930465

@Composable
fun LoadingFitFileScreen(
    readContext: Pair<ContentResolver, Uri>? = null,
    onComplete: (ParsedResponse?) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(space = 30.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Reading .fit file content")
        LinearProgressIndicator()
        readContext?.let { (contentResolver, uri) ->
            LaunchedEffect(true) {
                val fit = contentResolver.openInputStream(uri)?.buffered().use {
                    try {
                        FitDecoder().decode(it)
                    } catch (e: Exception) {
                        Log.e("fit", "Failed to parse fit file")
                        null
                    }
                }
                if (fit == null) {
                    onComplete(null)
                    return@LaunchedEffect
                }

                // We only support a single sport
                val session = fit.sessionMesgs.singleOrNull()
                if (session == null) {
                    onComplete(null)
                    return@LaunchedEffect
                }

                val startTime = session.startTime.date.toInstant()
                val endTime = session.timestamp.date.toInstant()

                val datapoints = fit.recordMesgs.map { it.timestamp.date.toInstant() to it }
                    .sortedBy { it.first }.distinctBy { it.first }.filter { (pointTime, _) ->
                        // Health Connect don't allow inclusive datapoints,
                        // so it must be after start and before (not including) the end
                        pointTime.isAfter(startTime) && pointTime.isBefore(endTime)
                    }
                val route = ArrayList<Location>(datapoints.size)
                val cadence = ArrayList<CyclingPedalingCadenceRecord.Sample>(datapoints.size)
                val heartrate = ArrayList<HeartRateRecord.Sample>(datapoints.size)
                val power = ArrayList<PowerRecord.Sample>(datapoints.size)
                val speed = ArrayList<SpeedRecord.Sample>(datapoints.size)

                for ((pointTime, point) in datapoints) {
                    listOf(
                        point.positionLat, point.positionLong
                    ).whenAllNotNull { (lat, long) ->
                        route.add(
                            Location(
                                time = pointTime,
                                altitude = point.altitude?.let(Float::toDouble)
                                    ?.let(Length::meters),
                                latitude = lat.toDouble() / FIT_GPS_COORD_SYSTEM,
                                longitude = long.toDouble() / FIT_GPS_COORD_SYSTEM,
                            )
                        )
                    }

                    point.heartRate?.let {
                        heartrate.add(
                            HeartRateRecord.Sample(
                                beatsPerMinute = it.toLong(),
                                time = pointTime,
                            )
                        )
                    }
                    point.cadence?.let {
                        cadence.add(
                            CyclingPedalingCadenceRecord.Sample(
                                time = pointTime,
                                revolutionsPerMinute = it.toDouble(),
                            )
                        )
                    }

                    point.power?.let {
                        power.add(
                            PowerRecord.Sample(
                                time = pointTime, power = it.watts,
                            )
                        )
                    }

                    point.speed?.let {
                        speed.add(
                            SpeedRecord.Sample(
                                speed = Velocity.metersPerSecond(it.toDouble()),
                                time = pointTime,
                            )
                        )
                    }
                }


                val result = ParsedResponse(
                    session = ExerciseSessionRecord(
                        startTime = startTime,
                        startZoneOffset = null,
                        endTime = endTime,
                        endZoneOffset = null,
                        metadata = METADATA,
                        exerciseType = when (session.sport) {
                            Sport.CYCLING -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
                            else -> ExerciseSessionType.EXERCISE_SESSION_TYPE_OTHER_WORKOUT
                        },
                        exerciseRoute = if (route.isNotEmpty()) {
                            ExerciseRoute(route)
                        } else {
                            null
                        }
                    ),
                    calories = TotalCaloriesBurnedRecord(
                        startTime = startTime,
                        startZoneOffset = null,
                        endTime = endTime,
                        endZoneOffset = null,
                        metadata = METADATA,
                        energy = session.totalCalories.kilocalories,
                    ),
                    distance = DistanceRecord(
                        startTime = startTime,
                        startZoneOffset = null,
                        endTime = endTime,
                        endZoneOffset = null,
                        metadata = METADATA,
                        distance = session.totalDistance.meters,
                    ),
                    heartRate = if (heartrate.isNotEmpty()) {
                        HeartRateRecord(
                            startTime = startTime,
                            startZoneOffset = null,
                            endTime = endTime,
                            endZoneOffset = null,
                            metadata = METADATA,
                            samples = heartrate,
                        )
                    } else {
                        null
                    },
                    power = if (power.isNotEmpty()) {
                        PowerRecord(
                            startTime = startTime,
                            startZoneOffset = null,
                            endTime = endTime,
                            endZoneOffset = null,
                            metadata = METADATA,
                            samples = power,
                        )
                    } else {
                        null
                    },
                    speed = if (speed.isNotEmpty()) {
                        SpeedRecord(
                            startTime = startTime,
                            startZoneOffset = null,
                            endTime = endTime,
                            endZoneOffset = null,
                            metadata = METADATA,
                            samples = speed,
                        )
                    } else {
                        null
                    },
                    cadence = if (cadence.isNotEmpty()) {
                        CyclingPedalingCadenceRecord(
                            startTime = startTime,
                            startZoneOffset = null,
                            endTime = endTime,
                            endZoneOffset = null,
                            metadata = METADATA,
                            samples = cadence
                        )
                    } else {
                        null
                    },
                )

                onComplete(result)
            }
        }
    }
}

data class ParsedResponse(
    val session: ExerciseSessionRecord,
    val calories: TotalCaloriesBurnedRecord,
    val distance: DistanceRecord,
    val heartRate: HeartRateRecord?,
    val power: PowerRecord?,
    val speed: SpeedRecord?,
    val cadence: CyclingPedalingCadenceRecord?,
)

@Preview(showSystemUi = true, showBackground = true)
@Composable
private fun LoadingFitFileScreenPreview() {
    LoadingFitFileScreen(onComplete = {})
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FitFilePreviewScreen(
    fitMessage: ParsedResponse,
    onSend: suspend () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeContentPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Top)
    ) {
        TopAppBar(navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Go Back"
                )
            }
        }, title = {
            Text("Exercise preview")
        })

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Top)
        ) {
            fitMessage.session.let {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Top)
                    ) {
                        Text("Exercise session", style = MaterialTheme.typography.titleSmall)
                        Text("Duration: ${Duration.between(it.startTime, it.endTime).toMinutes()} minutes")
                        Text("Start time: ${it.startTime}")
                        Text("End time: ${it.endTime}")
                        Text(
                            "Sport: " + when (it.exerciseType) {
                                ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "🚲"
                                else -> "Other"
                            }
                        )
                        it.exerciseRouteResult.let {
                            if (it is ExerciseRouteResult.Data) {
                                HorizontalDivider()
                                Text("GPS datapoints: ${it.exerciseRoute.route.size} records")
                            }
                        }
                    }
                }
            }

            fitMessage.distance.let {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Top)
                    ) {
                        Text("Distance", style = MaterialTheme.typography.titleSmall)
                        Text("Distance: ${it.distance.inKilometers} km")
                    }
                }
            }

            fitMessage.calories.let {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Top)
                    ) {
                        Text("Calories", style = MaterialTheme.typography.titleSmall)
                        Text("Calories: ${it.energy.inKilocalories} Cal")
                    }
                }
            }

            fitMessage.heartRate?.let {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Top)
                    ) {
                        val average by produceState("--") {
                            val avg = it.samples.map { it.beatsPerMinute }.average()
                            value = "%.2f bpm".format(avg)
                        }
                        Text("Heart Rate", style = MaterialTheme.typography.titleSmall)
                        Text("Recorded datapoints: ${it.samples.size} datapoints")
                        Text("Average heart rate: $average")
                    }
                }
            }

            fitMessage.speed?.let {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Top)
                    ) {
                        val average by produceState("--") {
                            val avg = it.samples.map { it.speed.inKilometersPerHour }.average()
                            value = "%.2f km/h".format(avg)
                        }
                        Text("Speed", style = MaterialTheme.typography.titleSmall)
                        Text("Recorded datapoints: ${it.samples.size} datapoints")
                        Text("Average speed: $average")
                    }
                }
            }

            fitMessage.power?.let {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Top)
                    ) {
                        val average by produceState("--") {
                            val avg = it.samples.map { it.power.inWatts }.average()
                            value = "%.2f watts".format(avg)
                        }
                        Text("Power", style = MaterialTheme.typography.titleSmall)
                        Text("Recorded datapoints: ${it.samples.size} datapoints")
                        Text("Average power: $average")
                    }
                }
            }

            fitMessage.cadence?.let {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Top)
                    ) {
                        val average by produceState("--") {
                            val avg = it.samples.map { it.revolutionsPerMinute }.average()
                            value = "%.2f rpm".format(avg)
                        }
                        Text("Cadence", style = MaterialTheme.typography.titleSmall)
                        Text("Recorded datapoints: ${it.samples.size} datapoints")
                        Text("Average cadence: $average")
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            var processing by remember { mutableStateOf(false) }
            val tasks = rememberCoroutineScope()
            Button(
                enabled = !processing,
                onClick = {
                    processing = true
                    tasks.launch {
                        try {
                            onSend()
                        } catch (e: Exception) {
                            Log.e("connect", "Failed to submit to Health Connect", e)
                        }
                    }.invokeOnCompletion {
                        processing = false
                    }
                },
                modifier = Modifier.padding(ButtonDefaults.TextButtonWithIconContentPadding),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_health_connect_logo),
                    contentDescription = "Health Connect Logo",
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
                Text("Send to Health Connect")
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, device = "id:Nexus 4")
@Composable
private fun FitFilePreviewScreenPreview() {
    val startTime = Instant.parse("2020-01-01T01:01:01Z")
    val endTime = Instant.parse("2020-01-01T02:02:02Z")

    FitFilePreviewScreen(
        onSend = {}, onBack = {},
        fitMessage = ParsedResponse(
            session = ExerciseSessionRecord(
                startTime = startTime,
                startZoneOffset = null,
                endTime = endTime,
                endZoneOffset = null,
                metadata = METADATA,
                exerciseType = ExerciseSessionType.EXERCISE_SESSION_TYPE_BIKING,
                exerciseRoute = ExerciseRoute(
                    route = listOf(
                        Location(startTime, 1.0, 1.0),
                        Location(startTime.plusSeconds(1), 1.1, 1.1),
                    )
                )
            ),
            calories = TotalCaloriesBurnedRecord(
                startTime = startTime,
                startZoneOffset = null,
                endTime = endTime,
                endZoneOffset = null,
                metadata = METADATA,
                energy = Energy.calories(100.0),
            ),
            distance = DistanceRecord(
                startTime = startTime,
                startZoneOffset = null,
                endTime = endTime,
                endZoneOffset = null,
                metadata = METADATA,
                distance = Length.meters(100.0),
            ),
            heartRate = HeartRateRecord(
                startTime = startTime,
                startZoneOffset = null,
                endTime = endTime,
                endZoneOffset = null,
                metadata = METADATA,
                samples = listOf(
                    HeartRateRecord.Sample(startTime, 68),
                    HeartRateRecord.Sample(startTime.plusSeconds(1), 140),
                )
            ),
            power = PowerRecord(
                startTime = startTime,
                startZoneOffset = null,
                endTime = endTime,
                endZoneOffset = null,
                metadata = METADATA,
                samples = listOf(
                    PowerRecord.Sample(startTime, Power.watts(100.0)),
                    PowerRecord.Sample(startTime.plusSeconds(1), Power.watts(500.0))
                )
            ),
            speed = SpeedRecord(
                startTime = startTime,
                startZoneOffset = null,
                endTime = endTime,
                endZoneOffset = null,
                metadata = METADATA,
                samples = listOf(
                    SpeedRecord.Sample(startTime, Velocity.metersPerSecond(1.9)),
                    SpeedRecord.Sample(
                        startTime.plusSeconds(1), Velocity.metersPerSecond(5.9)
                    ),
                )
            ),
            cadence = CyclingPedalingCadenceRecord(
                startTime = startTime,
                startZoneOffset = null,
                endTime = endTime,
                endZoneOffset = null,
                metadata = METADATA,
                samples = listOf(
                    CyclingPedalingCadenceRecord.Sample(startTime, 80.0),
                    CyclingPedalingCadenceRecord.Sample(startTime.plusSeconds(1), 89.0),
                )
            ),
        ),
    )
}

fun <T : Any, R : Any> Collection<T?>.whenAllNotNull(block: (List<T>) -> R) {
    val values = this.filterNotNull()
    if (values.size == this.size) {
        block(values)
    }
}
