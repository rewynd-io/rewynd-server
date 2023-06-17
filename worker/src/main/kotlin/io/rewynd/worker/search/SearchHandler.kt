package io.rewynd.worker.search

import arrow.core.identity
import io.rewynd.model.*
import io.rewynd.common.SearchJobHandler
import io.rewynd.common.ServerEpisodeInfo
import io.rewynd.common.ServerSeasonInfo
import io.rewynd.common.ServerShowInfo
import io.rewynd.common.database.Database
import io.rewynd.worker.deserializeDirectory
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import mu.KotlinLogging
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.StringField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.StoredFields
import org.apache.lucene.index.Term
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.ScoreDoc
import org.apache.lucene.search.TermQuery
import org.apache.lucene.search.spell.LevenshteinDistance
import org.apache.lucene.search.spell.LuceneDictionary
import org.apache.lucene.search.suggest.analyzing.BlendedInfixSuggester
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt


val log = KotlinLogging.logger { }

data class SearchIndex(
    val suggester: BlendedInfixSuggester,
    val searcher: IndexSearcher,
    val lastUpdated: Instant,
    val libraryId: String
)

class SearchHandler(val db: Database) {
    val libIndicies = ConcurrentHashMap<String, SearchIndex>()

    val jobHander: SearchJobHandler = { context ->
        if (context.request.text.isBlank()) {
            SearchResponse(emptyList())
        } else {
            libIndicies.elements().asIterator().asFlow().flatMapMerge { index ->
                val storedFields = index.searcher.storedFields()
                index.suggester.lookup(context.request.text, false, 100).asFlow().mapNotNull { lookupResult ->
                    index.searcher.search(
                        TermQuery(Term("title", lookupResult.key.toString())),
                        1
                    ).scoreDocs.firstOrNull()
                        ?.toSearchResult(storedFields, context.request.text)
                }
            }.let { SearchResponse(it.toList()) }
        }
    }

    suspend fun updateIndicies() {
        this.db.listLibraries().asFlow().mapNotNull { library ->
            val existingIndex = libIndicies[library.name]
            db.getLibraryIndex(library.name, existingIndex?.lastUpdated)?.let { libraryIndex ->
                val index = deserializeDirectory(libraryIndex.index)
                val indexReader = DirectoryReader.open(index)
                val dictionary = LuceneDictionary(indexReader, "title")
                val suggester = BlendedInfixSuggester(index, StandardAnalyzer()).apply { build(dictionary) }
                val searcher = IndexSearcher(indexReader)
                SearchIndex(suggester, searcher, libraryIndex.lastUpdated, libraryIndex.libraryId)
            }

        }.collect {
            libIndicies[it.libraryId] = it
        }
    }
}

private val levenshteinDistance = LevenshteinDistance()
private fun ScoreDoc.toSearchResult(storedFields: StoredFields, searchText: String): SearchResult {
    val doc = storedFields.document(this.doc)
    val type = SearchResultType.valueOf(doc.get("type"))
    val description = doc.get("description")
    val title = doc.get("title")
    val id = doc.get("id")
    return SearchResult(
        resultType = type,
        id = id,
        title = title,
        description = description,
        score = levenshteinDistance.getDistance(title, searchText).toDouble()
    )
}

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