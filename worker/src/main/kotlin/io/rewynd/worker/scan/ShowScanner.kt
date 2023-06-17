package io.rewynd.worker.scan

import arrow.core.identity
import arrow.core.mapNotNull
import arrow.fx.coroutines.parMapUnordered
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import io.rewynd.model.*
import io.rewynd.common.*
import io.rewynd.common.database.Database
import io.rewynd.worker.ffprobe.FfprobeResult
import io.rewynd.worker.serialize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.StringField
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.store.ByteBuffersDirectory
import java.io.File
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val imageExtensions = setOf("jpg", "jpeg", "png")
private val subtitleExtensions = setOf("srt", "wvtt", "vtt")
private val subtitleCodecs = setOf("subrip", "srt", "webvtt", "wvtt")

data class ShowScanResults(
    val images: Set<ServerImageInfo> = emptySet(),
    val shows: Set<ServerShowInfo> = emptySet(),
    val seasons: Set<ServerSeasonInfo> = emptySet(),
    val episodes: Set<ServerEpisodeInfo> = emptySet()
) {
    operator fun plus(other: ShowScanResults) = ShowScanResults(
        images = this.images + other.images,
        episodes = this.episodes + other.episodes,
        seasons = this.seasons + other.seasons,
        shows = this.shows + other.shows
    )

    companion object {
        val EMPTY = ShowScanResults()

    }
}

class ShowScanner(private val lib: Library, private val db: Database) : Scanner {
    override suspend fun scan() {
        val memoryIndex: ByteBuffersDirectory = ByteBuffersDirectory()
        val analyzer = StandardAnalyzer()
        val start = Clock.System.now()
        lib.rootPaths.forEach { root ->
            File(root).walk().maxDepth(1).filter {
                it.isDirectory && it.absolutePath != Path(root).absolutePathString() && !it.name.startsWith(".") && it.nameWithoutExtension.isNotBlank()
            }.asFlow().parMapUnordered { showFolder ->
                withTimeoutOrNull(10.minutes) {
                    with(scanShow(showFolder, lib)) {
                        if (this.episodes.isNotEmpty()) {
                            images.mapNotNull {
                                try {
                                    db.upsertImage(it)
                                    null // TODO may want to index these at some point
                                } catch (e: Exception) {
                                    log.error(e) { "Failed to upsert image $it" }
                                    null
                                }
                            } +
                                    shows.mapNotNull {
                                        try {
                                            db.upsertShow(it)
                                            it.toDocument()
                                        } catch (e: Exception) {
                                            log.error(e) { "Failed to upsert show $it" }
                                            null
                                        }
                                    } +
                                    seasons.mapNotNull {
                                        try {
                                            db.upsertSeason(it)
                                            it.toDocument()
                                        } catch (e: Exception) {
                                            log.error(e) { "Failed to upsert image $it" }
                                            null
                                        }
                                    } +
                                    episodes.mapNotNull {
                                        try {
                                            db.upsertEpisode(it)
                                            it.toDocument()
                                        } catch (e: Exception) {
                                            log.error(e) { "Failed to upsert image $it" }
                                            null
                                        }
                                    }
                        } else emptyList<Document>()
                    }.asFlow()
                } ?: emptyFlow()
            }.flattenMerge().flowOn(Dispatchers.IO).fold(IndexWriter(
                memoryIndex,
                IndexWriterConfig(analyzer).apply { commitOnClose = true }
            )) { acc, value ->
                io.rewynd.worker.search.log.info { "Adding $value" }
                acc.addDocument(value)
                acc
            }.close()
        }

        db.upsertLibraryIndex(LibraryIndex(memoryIndex.serialize(), lib.name, start))

        db.cleanEpisodes(start, lib.name)
        db.cleanSeasons(start, lib.name)
        db.cleanShows(start, lib.name)
        db.cleanImages(start, lib.name)
    }

    private suspend fun scanShow(showDir: File, lib: Library): ShowScanResults {
        val showId = showDir.id()
        val nfo =
            Path(showDir.absolutePath, "tvshow.nfo").let { if (it.exists()) it else null }?.readText()
                ?.parseShowNfo()
        log.info { nfo }
        val folderImage = showDir.findFolderImage()
        val backdropImage = showDir.findBackdropImage()

        val showInfo = ServerShowInfo(
            showInfo = ShowInfo(
                id = showId,
                libraryId = lib.name,
                title = nfo?.title ?: showDir.nameWithoutExtension,
                plot = nfo?.plot,
                outline = nfo?.outline,
                originalTitle = nfo?.originaltitle,
                premiered = nfo?.premiered,
                releaseDate = nfo?.releasedate,
                endDate = nfo?.enddate,
                mpaa = nfo?.mpaa,
                imdbId = nfo?.imdb_id,
                tmdbId = nfo?.tmdbid,
                tvdbId = nfo?.tvdbid,
                tvRageId = nfo?.tvrageid,
                rating = nfo?.rating?.toDouble(),
                year = nfo?.year?.toDouble(),
                runTime = nfo?.runTime?.toDouble(),
                episode = null, // TODO wat?
                episodeNumberEnd = null, // TODO wat?
                season = null, // TODO wat?
                aired = null,
                genre = nfo?.genre,
                studio = nfo?.studio,
                status = nfo?.status,
                tag = nfo?.tag,
                actors = nfo?.actor,
                seriesImageId = folderImage?.imageId,
                backdropImageId = backdropImage?.imageId,
            ), libraryData = LibraryData(
                libraryId = lib.name,
            )

        )

        return showDir.walk().maxDepth(1).filter {
            it.isDirectory && it.absolutePath != showDir.absolutePath && !it.name.startsWith(".") && it.nameWithoutExtension.isNotBlank()
        }.fold(ShowScanResults.EMPTY) { acc, file ->
            acc + scanSeason(file, showInfo)
        } + ShowScanResults(shows = setOf(showInfo), images = setOfNotNull(folderImage, backdropImage)).also {
            log.info { "Processed ${showDir.absolutePath}" }
        }
    }

    private suspend fun scanSeason(seasonDir: File, showInfo: ServerShowInfo): ShowScanResults {
        val seasonId = seasonDir.id()
        val nfo = Path(seasonDir.absolutePath, "season.nfo").let {
            log.info { it }
            if (it.exists()) it else null
        }?.readText()?.parseSeasonNfo()
        log.info { nfo }
        val folderImage = seasonDir.findFolderImage()
        val seasonInfo = ServerSeasonInfo(
            seasonInfo = SeasonInfo(
                id = seasonId,
                showId = showInfo.showInfo.id,
                seasonNumber = (nfo?.seasonnumber ?: seasonDir.name.parseSeasonNumber() ?: 0).toDouble(),
                libraryId = lib.name,
                showName = showInfo.showInfo.title,
                year = nfo?.year?.toDouble(),
                premiered = nfo?.premiered,
                releaseDate = nfo?.releasedate,
                folderImageId = folderImage?.imageId,
                actors = listOf(),
            ), libraryData = LibraryData(
                libraryId = lib.name,
            )
        )

        return (seasonDir.walk().filter {
            it.isFile && !it.name.startsWith(".") && it.nameWithoutExtension.isNotBlank() && !imageExtensions.contains(
                it.extension
            ) && !subtitleExtensions.contains(it.extension) && it.extension != "nfo"
        }
            .fold(ShowScanResults.EMPTY) { acc, file ->

                acc + scanEpisode(file, seasonInfo, showInfo)
            } + ShowScanResults(
            images = setOfNotNull(folderImage),
            seasons = setOf(
                seasonInfo
            ),
        )).let { if (it.episodes.isNotEmpty()) it else ShowScanResults.EMPTY }.also {
            log.info { "Processed ${seasonDir.absolutePath}" }
        }
    }

    private suspend fun scanEpisode(
        episodeFile: File, seasonInfo: ServerSeasonInfo, showInfo: ServerShowInfo
    ): ShowScanResults = try {
        val episodeId = episodeFile.id()
        val curr = db.getEpisode(episodeId)
        val nfo = Path(episodeFile.parent, "${episodeFile.nameWithoutExtension}.nfo").let {
            log.info { it }
            if (it.exists()) it else null
        }?.readText()?.parseEpsiodeNfo()
        val subtitleFiles = episodeFile.parentFile.walk().maxDepth(1).filter {
            it.name.startsWith(episodeFile.nameWithoutExtension) && subtitleExtensions.contains(it.extension)
        }.associate { it.nameWithoutExtension to FileLocation.LocalFile(it.absolutePath) }
        val subtitleFileTracks = subtitleFiles.mapValues {
            FfprobeResult.parseFile(Path(it.value.path).toFile()).extractInfo().subtitleTracks.values.firstOrNull()
        }.mapNotNull { it.value }
        val episodeImageFile = episodeFile.findEpisodeImage()
        val ffprobe = curr?.let {
            if (episodeFile.lastModified() < it.mediaInfo.libraryData.lastUpdated.toEpochMilliseconds()) {
                it.toFfprobeInfo()
            } else {
                null
            }
        } ?: withTimeoutOrNull(5.seconds) {
            log.info { "Probing ${episodeFile.absolutePath}" }
            FfprobeResult.parseFile(episodeFile).extractInfo().also {
                log.info { "Done Probing ${episodeFile.absolutePath}" }
            }
        }
        if (ffprobe != null && ffprobe.videoTracks.isNotEmpty() && ffprobe.runTime > 1.0) {
            ShowScanResults(
                images = setOfNotNull(episodeImageFile),
                episodes = setOf(
                    ServerEpisodeInfo(
                        EpisodeInfo(
                            id = episodeId,
                            libraryId = this.lib.name,
                            audioTracks = ffprobe.audioTracks.toAudioTracks(),
                            videoTracks = ffprobe.videoTracks.toVideoTracks(),
                            subtitleTracks = ffprobe.subtitleTracks.toSubtitleTracks() + subtitleFileTracks.toSubtitleTracks(),
                            showId = showInfo.showInfo.id,
                            seasonId = seasonInfo.seasonInfo.id,
                            title = nfo?.title ?: episodeFile.nameWithoutExtension,
                            runTime = ffprobe.runTime,
                            plot = nfo?.plot,
                            outline = nfo?.outline,
                            director = nfo?.director,
                            writer = nfo?.writer,
                            credits = nfo?.credits,
                            rating = nfo?.rating,
                            year = nfo?.year?.toDouble(),
                            episode = nfo?.episode?.toDouble(),
                            episodeNumberEnd = nfo?.episodenumberend?.toDouble(),
                            season = seasonInfo.seasonInfo.seasonNumber,
                            showName = showInfo.showInfo.title,
                            aired = null, //TODO nfo?.aired is and should be a string, not a double
                            episodeImageId = episodeImageFile?.imageId
                        ), ServerMediaInfo(
                            mediaInfo = MediaInfo(
                                id = episodeId,
                                libraryId = lib.name,
                                audioTracks = ffprobe.audioTracks.toAudioTracks(),
                                videoTracks = ffprobe.videoTracks.toVideoTracks(),
                                subtitleTracks = ffprobe.subtitleTracks.toSubtitleTracks() + subtitleFileTracks.toSubtitleTracks(),
                                runTime = 0.0,
                            ),
                            libraryData = LibraryData(
                                libraryId = lib.name,
                            ),
                            fileInfo = FileInfo(
                                location = FileLocation.LocalFile(episodeFile.absolutePath),
                                size = Files.size(episodeFile.toPath()),
                            ),
                            subtitleFiles = subtitleFiles,
                            videoTracks = ffprobe.videoTracks,
                            audioTracks = ffprobe.audioTracks,
                            subtitleTracks = ffprobe.subtitleTracks + subtitleFileTracks,
                        )
                    )
                )
            )
        } else ShowScanResults.EMPTY
    } catch (e: Exception) {
        log.error(e) { "Failed to process $episodeFile" }
        ShowScanResults.EMPTY
    }.also {
        log.info { "Processed ${episodeFile.absolutePath}" }
    }


    data class SeasonNfo(
        val title: String?,
        val year: Int?,
        val premiered: String?,
        val releasedate: String?,
        val seasonnumber: Int?,
        @JacksonXmlElementWrapper(useWrapping = false) val actor: List<Actor>?,
    )

    data class EpisodeNfo(
        val plot: String?,
        val outline: String?,
        val title: String?,
        @JacksonXmlElementWrapper(useWrapping = false) val director: List<String>?,
        @JacksonXmlElementWrapper(useWrapping = false) val writer: List<String>?,
        @JacksonXmlElementWrapper(useWrapping = false) val credits: List<String>?,
        val rating: Double?,
        val year: Int?,
        val runtime: Double?,
        @JacksonXmlElementWrapper(useWrapping = false) val actor: List<Actor>?,
        val episode: Int?,
        val episodenumberend: Int?,
        val season: Int?,
        val aired: String?
    )

    data class ShowNfo(
        val plot: String?,
        val outline: String?,
        val dateadded: String?, // TODO should be a Date
        val title: String?,
        val originaltitle: String?,
        val rating: Number?,
        val year: Number?,
        val mpaa: String?, // TODO could probably be an enum
        val imdb_id: String?,
        val tmdbid: String?,
        val tvdbid: String?,
        val tvrageid: String?,
        val premiered: String?, // TODO should be a Date
        val releasedate: String?, // TODO should be a Date
        val enddate: String?, // TODO should be a Date
        val runTime: Number?,
        val genre: String?,
        val studio: String?,
        @JacksonXmlElementWrapper(useWrapping = false) val tag: List<String>?,
        @JacksonXmlElementWrapper(useWrapping = false) val actor: List<Actor>?,
        val status: String?,
    )

    private val xmlMapper = XmlMapper().apply {
        registerModule(
            KotlinModule.Builder().build()
        )
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private fun String.parseSeasonNfo(): SeasonNfo? = try {
        xmlMapper.readValue(this.trim())
    } catch (e: Exception) {
        log.error(e) { "Failed to parse XML: $this" }
        null
    }


    private fun String.parseEpsiodeNfo(): EpisodeNfo? = try {
        xmlMapper.readValue(this.trim())
    } catch (e: Exception) {
        log.error(e) { "Failed to parse XML: $this" }
        null
    }


    private fun String.parseShowNfo(): ShowNfo? = try {
        xmlMapper.readValue(this.trim())
    } catch (e: Exception) {
        log.error(e) { "Failed to parse XML: $this" }
        null
    }

    private fun File.findEpisodeImage(): ServerImageInfo? =
        (Path(this.parent).toFile().walk().maxDepth(2).filter {
            it.name.startsWith(this.nameWithoutExtension) && imageExtensions.contains(it.extension)
        }.firstOrNull())?.let {
            ServerImageInfo(
                fileInfo = FileInfo(
                    location = FileLocation.LocalFile(it.absolutePath),
                    size = 0L,
                ), libraryData = LibraryData(
                    libraryId = this@ShowScanner.lib.name,
                ), imageId = it.id()
            )
        }


    private fun File.findFolderImage(): ServerImageInfo? = walk().maxDepth(1).filter {
        it.nameWithoutExtension == "folder" && imageExtensions.contains(it.extension)
    }.firstOrNull()?.let {
        ServerImageInfo(
            fileInfo = FileInfo(
                location = FileLocation.LocalFile(it.absolutePath),
                size = 0L,
            ), libraryData = LibraryData(
                libraryId = this@ShowScanner.lib.name,
            ), imageId = it.id()
        )
    }

    private fun File.findBackdropImage(): ServerImageInfo? = walk().maxDepth(1).filter {
        listOf("backdrop", "banner").contains(it.nameWithoutExtension) && imageExtensions.contains(it.extension)
    }.firstOrNull()?.let {
        ServerImageInfo(
            fileInfo = FileInfo(
                location = FileLocation.LocalFile(it.absolutePath),
                size = 0L,
            ), libraryData = LibraryData(
                libraryId = this@ShowScanner.lib.name,
            ), imageId = it.id()
        )
    }

    private fun File.id() = md5("${lib.name}:${this.absolutePath}")

    companion object : KLog()
}


private fun String.parseSeasonNumber(): Int? = split(" ").lastOrNull()?.toIntOrNull()

private fun ServerEpisodeInfo.toFfprobeInfo(): FfprobeInfo = FfprobeInfo(
    this.mediaInfo.audioTracks,
    this.mediaInfo.videoTracks,
    this.mediaInfo.subtitleTracks,
    this.episodeInfo.runTime
)

data class FfprobeInfo(
    val audioTracks: Map<String, ServerAudioTrack>,
    val videoTracks: Map<String, ServerVideoTrack>,
    val subtitleTracks: Map<String, ServerSubtitleTrack>,
    val runTime: Double
)

private fun FfprobeResult.extractInfo(): FfprobeInfo {
    val audioTracks = this.streams.filter { it.codecType == "audio" }.associate {
        val name = "${it.index} - ${it.codecName} - ${it.tags?.language}"

        name to ServerAudioTrack(
            id = it.id,
            index = it.index,
            codecName = it.codecName,
            codecLongName = it.codecLongName,
            profile = it.profile,
            codecType = it.codecType,
            codecTagString = it.codecTagString,
            codecTag = it.codecTag,
            width = it.width,
            height = it.height,
            codedWidth = it.codedWidth,
            codedHeight = it.codedHeight,
            closedCaptions = it.closedCaptions,
            filmGrain = it.filmGrain,
            hasBFrames = it.hasBFrames,
            sampleAspectRatio = it.sampleAspectRatio,
            displayAspectRatio = it.displayAspectRatio,
            pixFmt = it.pixFmt,
            level = it.level,
            colorRange = it.colorRange,
            chromaLocation = it.chromaLocation,
            refs = it.refs,
            rFrameRate = it.rFrameRate,
            avgFrameRate = it.avgFrameRate,
            timeBase = it.timeBase,
            startPts = it.startPts,
            duration = it.duration,
            durationTs = it.durationTs,
            startTime = it.startTime,
            extradataSize = it.extradataSize,
            default = it.disposition?.default,
            dub = it.disposition?.dub,
            original = it.disposition?.original,
            comment = it.disposition?.comment,
            lyrics = it.disposition?.lyrics,
            karaoke = it.disposition?.karaoke,
            forced = it.disposition?.forced,
            hearingImpaired = it.disposition?.hearingImpaired,
            visualImpaired = it.disposition?.visualImpaired,
            cleanEffects = it.disposition?.cleanEffects,
            attachedPic = it.disposition?.attachedPic,
            timedThumbnails = it.disposition?.timedThumbnails,
            captions = it.disposition?.captions,
            descriptions = it.disposition?.descriptions,
            metadata = it.disposition?.metadata,
            dependent = it.disposition?.dependent,
            stillImage = it.disposition?.stillImage,
            creationTime = it.tags?.creationTime,
            language = it.tags?.language,
            encoder = it.tags?.encoder,
        )
    }
    val videoTracks = this.streams.filter { it.codecType == "video" }.associate {
        val name = "${it.index} - ${it.codecName} - ${it.tags?.language}"

        name to ServerVideoTrack(
            id = it.id,
            index = it.index,
            codecName = it.codecName,
            codecLongName = it.codecLongName,
            profile = it.profile,
            codecType = it.codecType,
            codecTagString = it.codecTagString,
            codecTag = it.codecTag,
            width = it.width,
            height = it.height,
            codedWidth = it.codedWidth,
            codedHeight = it.codedHeight,
            closedCaptions = it.closedCaptions,
            filmGrain = it.filmGrain,
            hasBFrames = it.hasBFrames,
            sampleAspectRatio = it.sampleAspectRatio,
            displayAspectRatio = it.displayAspectRatio,
            pixFmt = it.pixFmt,
            level = it.level,
            colorRange = it.colorRange,
            chromaLocation = it.chromaLocation,
            refs = it.refs,
            rFrameRate = it.rFrameRate,
            avgFrameRate = it.avgFrameRate,
            timeBase = it.timeBase,
            startPts = it.startPts,
            duration = it.duration,
            durationTs = it.durationTs,
            startTime = it.startTime,
            extradataSize = it.extradataSize,
            default = it.disposition?.default,
            dub = it.disposition?.dub,
            original = it.disposition?.original,
            comment = it.disposition?.comment,
            lyrics = it.disposition?.lyrics,
            karaoke = it.disposition?.karaoke,
            forced = it.disposition?.forced,
            hearingImpaired = it.disposition?.hearingImpaired,
            visualImpaired = it.disposition?.visualImpaired,
            cleanEffects = it.disposition?.cleanEffects,
            attachedPic = it.disposition?.attachedPic,
            timedThumbnails = it.disposition?.timedThumbnails,
            captions = it.disposition?.captions,
            descriptions = it.disposition?.descriptions,
            metadata = it.disposition?.metadata,
            dependent = it.disposition?.dependent,
            stillImage = it.disposition?.stillImage,
            creationTime = it.tags?.creationTime,
            language = it.tags?.language,
            encoder = it.tags?.encoder,
        )
    }

    val subtitleTracks = this.streams.filter {
        it.codecType == "subtitle" && subtitleCodecs.contains(
            it.codecName
        )
    }.associate {
        val name = "${it.index} - ${it.codecName} - ${it.tags?.language}"

        name to ServerSubtitleTrack(
            id = it.id,
            index = it.index,
            codecName = it.codecName,
            codecLongName = it.codecLongName,
            profile = it.profile,
            codecType = it.codecType,
            codecTagString = it.codecTagString,
            codecTag = it.codecTag,
            width = it.width,
            height = it.height,
            codedWidth = it.codedWidth,
            codedHeight = it.codedHeight,
            closedCaptions = it.closedCaptions,
            filmGrain = it.filmGrain,
            hasBFrames = it.hasBFrames,
            sampleAspectRatio = it.sampleAspectRatio,
            displayAspectRatio = it.displayAspectRatio,
            pixFmt = it.pixFmt,
            level = it.level,
            colorRange = it.colorRange,
            chromaLocation = it.chromaLocation,
            refs = it.refs,
            rFrameRate = it.rFrameRate,
            avgFrameRate = it.avgFrameRate,
            timeBase = it.timeBase,
            startPts = it.startPts,
            duration = it.duration,
            durationTs = it.durationTs,
            startTime = it.startTime,
            extradataSize = it.extradataSize,
            default = it.disposition?.default,
            dub = it.disposition?.dub,
            original = it.disposition?.original,
            comment = it.disposition?.comment,
            lyrics = it.disposition?.lyrics,
            karaoke = it.disposition?.karaoke,
            forced = it.disposition?.forced,
            hearingImpaired = it.disposition?.hearingImpaired,
            visualImpaired = it.disposition?.visualImpaired,
            cleanEffects = it.disposition?.cleanEffects,
            attachedPic = it.disposition?.attachedPic,
            timedThumbnails = it.disposition?.timedThumbnails,
            captions = it.disposition?.captions,
            descriptions = it.disposition?.descriptions,
            metadata = it.disposition?.metadata,
            dependent = it.disposition?.dependent,
            stillImage = it.disposition?.stillImage,
            creationTime = it.tags?.creationTime,
            language = it.tags?.language,
            encoder = it.tags?.encoder,
        )
    }

    return FfprobeInfo(audioTracks, videoTracks, subtitleTracks, this.duration())
}

private fun FfprobeResult.duration() =
    (this.format?.duration ?: this.streams.mapNotNull { it.duration }.maxOrNull() ?: 0.0)


private fun ServerSeasonInfo.toDocument() = Document().apply {
    identity(this@toDocument.seasonInfo.id)
    add(StringField("title", this@toDocument.seasonInfo.formatTitle(), Field.Store.YES))
    add(StoredField("id", this@toDocument.seasonInfo.id))
    add(StoredField("description", this@toDocument.seasonInfo.formatTitle()))
    add(StoredField("type", SearchResultType.season.name))
}

private fun SeasonInfo.formatTitle() = "${showName} - Season ${seasonNumber.roundToInt()}"

private fun ServerEpisodeInfo.toDocument() = Document().apply {
    identity(this@toDocument.episodeInfo.id)
    add(StringField("title", this@toDocument.episodeInfo.formatTitle(), Field.Store.YES))
    add(StoredField("id", this@toDocument.episodeInfo.id))
    add(StoredField("description", this@toDocument.episodeInfo.plot ?: this@toDocument.episodeInfo.outline ?: ""))
    add(StoredField("type", SearchResultType.episode.name))
}

private fun ServerShowInfo.toDocument() = Document().apply {
    identity(this@toDocument.showInfo.id)
    add(StringField("title", this@toDocument.showInfo.title, Field.Store.YES))
    add(StoredField("id", this@toDocument.showInfo.id))
    add(StoredField("description", this@toDocument.showInfo.plot ?: this@toDocument.showInfo.outline ?: ""))
    add(StoredField("type", SearchResultType.show.name))
}

private fun EpisodeInfo.formatTitle() =
    "$showName - S${"%02d".format(season?.roundToInt() ?: 0)}E${"%02d".format(episode?.roundToInt() ?: 0)}${
        episodeNumberEnd?.let {
            "-%02d".format(it.roundToInt())
        } ?: ""
    } - $title"
