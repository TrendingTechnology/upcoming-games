package com.gavinsappcreations.upcominggames.repository

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.paging.LivePagedListBuilder
import androidx.paging.PagedList
import com.gavinsappcreations.upcominggames.database.buildFinalQuery
import com.gavinsappcreations.upcominggames.database.getDatabase
import com.gavinsappcreations.upcominggames.domain.Game
import com.gavinsappcreations.upcominggames.domain.GameDetail
import com.gavinsappcreations.upcominggames.domain.SortOptions
import com.gavinsappcreations.upcominggames.network.GameNetwork
import com.gavinsappcreations.upcominggames.network.asDatabaseModel
import com.gavinsappcreations.upcominggames.network.asDomainModel
import com.gavinsappcreations.upcominggames.utilities.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class GameRepository private constructor(application: Application) {

    private val database = getDatabase(application)

    private val prefs: SharedPreferences =
        application.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)

    val sortOptions = MutableLiveData<SortOptions>()

    init {
        // Fetch sort options from SharedPrefs
        val releaseDateType: ReleaseDateType = enumValueOf(
            prefs.getString(
                KEY_RELEASE_DATE_TYPE,
                ReleaseDateType.RecentAndUpcoming.name
            )!!
        )
        val sortDirection: SortDirection =
            enumValueOf(prefs.getString(KEY_SORT_DIRECTION, SortDirection.Ascending.name)!!)

        val customDateStart = prefs.getString(KEY_CUSTOM_DATE_START, "")!!
        val customDateEnd = prefs.getString(KEY_CUSTOM_DATE_END, "")!!

        val platformIndices: MutableSet<Int> =
            prefs.getStringSet(KEY_PLATFORM_INDICES, setOf())!!.map {
            it.toInt()
        }.toMutableSet()


        // Set all sort options to _sortOptions at once
        sortOptions.value = SortOptions(
            releaseDateType,
            sortDirection,
            customDateStart,
            customDateEnd,
            platformIndices
        )
    }

    // Update value of _sortOptions and also save that value to SharedPrefs.
    fun updateSortOptions(newSortOptions: SortOptions) {
        sortOptions.value = newSortOptions

        prefs.edit().putString(KEY_SORT_DIRECTION, newSortOptions.sortDirection.name)
            .putString(KEY_RELEASE_DATE_TYPE, newSortOptions.releaseDateType.name)
            .putString(KEY_CUSTOM_DATE_START, newSortOptions.customDateStart)
            .putString(KEY_CUSTOM_DATE_END, newSortOptions.customDateEnd)
                // Convert MutableSet<Int> to Set<String> so we can store it in SharedPrefs
            .putStringSet(KEY_PLATFORM_INDICES, newSortOptions.platformIndices.map {
                it.toString()
            }.toSet())
            .apply()
    }


    fun getGameList(): LiveData<PagedList<Game>> {

        val dateConstraints = fetchDateConstraints()

        val query = buildFinalQuery(
            sortOptions.value!!.sortDirection.direction,
            dateConstraints[0],
            dateConstraints[1],
            sortOptions.value!!.platformIndices
        )

        val dataSourceFactory =
            database.gameDao.getGameList(query)

        // Get the paged list
        val data = LivePagedListBuilder(dataSourceFactory, DATABASE_PAGE_SIZE)
            .build()

        return data
    }


    private fun fetchDateConstraints(): Array<Long?> {

        var dateStartMillis: Long?
        var dateEndMillis: Long?

        val calendar: Calendar = Calendar.getInstance()
        val currentTimeMillis = calendar.timeInMillis

        when (sortOptions.value!!.releaseDateType) {
            ReleaseDateType.RecentAndUpcoming -> {
                // dateFilterStart is set to one week before current day.
                calendar.set(Calendar.DAY_OF_YEAR, calendar.get(Calendar.DAY_OF_YEAR) - 7)
                dateStartMillis = calendar.timeInMillis

                // dateFilterEnd is set to a far-off date so that every future game will be listed.
                calendar.set(Calendar.YEAR, calendar.get(Calendar.YEAR) + 100)
                dateEndMillis = calendar.timeInMillis
            }
            ReleaseDateType.Any -> {
                dateStartMillis = null
                dateEndMillis = null
            }
            ReleaseDateType.PastMonth -> {
                // dateFilterStart is set to one month before current day.
                calendar.set(Calendar.MONTH, calendar.get(Calendar.MONTH) - 1)
                dateStartMillis = calendar.timeInMillis

                // dateFilterEnd is set to current time.
                dateEndMillis = currentTimeMillis
            }
            ReleaseDateType.PastYear -> {
                // dateFilterStart is set to one year before current day.
                calendar.set(Calendar.YEAR, calendar.get(Calendar.YEAR) - 1)
                dateStartMillis = calendar.timeInMillis

                // dateFilterEnd is set to current time.
                dateEndMillis = currentTimeMillis
            }
            ReleaseDateType.CustomDate -> {
                val df = SimpleDateFormat("MM/dd/yyyy")

                val startDateString = sortOptions.value!!.customDateStart
                calendar.time = df.parse(startDateString)!!
                dateStartMillis = calendar.timeInMillis

                val endDateString = sortOptions.value!!.customDateEnd
                calendar.time = df.parse(endDateString)!!
                dateEndMillis = calendar.timeInMillis

                // If the end date is before the start date, just flip them.
                if (dateEndMillis < dateStartMillis) {
                    val temp = dateStartMillis
                    dateStartMillis = dateEndMillis
                    dateEndMillis = temp
                }
            }
        }

        return arrayOf(dateStartMillis, dateEndMillis)
    }


    private fun fetchPlatformAbbreviations(platformIndices: MutableSet<Int>): String {
        val sb = StringBuilder()

        // Game.platforms LIKE '%XONE%' OR Game.platforms LIKE '%PC%'

        for (platformIndex in platformIndices) {
            sb.append("platforms LIKE '%${allPlatforms[platformIndex].abbreviation}%'")
            sb.append(" OR ")
        }
        return sb.toString().removeSuffix(" OR ")
    }


    // TODO: modify this to fetch new games with WorkManager (query API using lastUpdated field as filter)
    suspend fun downloadGameListData(offset: Int) {
        val gameList = GameNetwork.gameData.getGameListData(
            API_KEY,
            "${ApiField.Json}",
            "${ApiField.OriginalReleaseDate}:${SortDirection.Ascending}",
            "",
            "${ApiField.Id}," +
                    "${ApiField.Guid}," +
                    "${ApiField.Name}," +
                    "${ApiField.Image}," +
                    "${ApiField.Platforms}," +
                    "${ApiField.OriginalReleaseDate}," +
                    "${ApiField.ExpectedReleaseDay}," +
                    "${ApiField.ExpectedReleaseMonth}," +
                    "${ApiField.ExpectedReleaseYear}," +
                    "${ApiField.ExpectedReleaseQuarter}",
            offset
        ).body()!!.games

        withContext(Dispatchers.IO) {
            database.gameDao.insertAll(gameList.asDatabaseModel())
        }
    }


    suspend fun downloadGameDetailData(guid: String): GameDetail {
        return GameNetwork.gameData.getGameDetailData(
            guid,
            API_KEY,
            "json",
            "id,guid,name,image,images,platforms," +
                    "original_release_date,expected_release_day,expected_release_month," +
                    "expected_release_year,expected_release_quarter,original_game_rating,developers,publishers,genres," +
                    "deck"
        ).body()!!.gameDetails.asDomainModel()
    }


    companion object {
        // For Singleton instantiation
        @Volatile
        private var instance: GameRepository? = null

        fun getInstance(application: Application) =
            instance ?: synchronized(this) {
                instance ?: GameRepository(application).also { instance = it }
            }
    }

}

