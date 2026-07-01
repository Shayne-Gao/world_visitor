package io.shayne.fogvisitor

data class TrackRecord(
    val id: String,
    val timestamp: Long,
    val source: String,
    val brushRadiusKm: Double,
    val encodedPath: String,
    val bbox: List<Double>? = null
)

data class AppMetadata(
    val totalAreaKm2: Double,
    val createdAt: Long
)

data class SourceOfTruth(
    val tracks: List<TrackRecord>
)

data class AppDataEnvelope(
    val version: String = "2.0.0",
    val metadata: AppMetadata,
    val sourceOfTruth: SourceOfTruth
)
