package com.nexastream.app.utils

import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Movie
import com.nexastream.app.models.People
import com.nexastream.app.models.SportMatch
import com.nexastream.app.models.TvShow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

private object ArtworkRepairCoordinator {
    private val repairScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightRepairs = ConcurrentHashMap.newKeySet<String>()

    fun shouldRepair(url: String?, error: GlideException?): Boolean {
        return ArtworkRepair.shouldRepair(url, error)
    }

    fun repairMovieArtwork(
        imageView: ImageView,
        movie: Movie,
        staleUrl: String,
        onUpdated: (Movie) -> Unit,
    ) {
        val provider = UserPreferences.currentProvider ?: return
        val repairKey = "${provider.name}|movie|${movie.id}|$staleUrl"
        if (!inFlightRepairs.add(repairKey)) return

        repairScope.launch {
            try {
                val database = AppDatabase.getInstance(imageView.context)
                val refreshedMovie = ArtworkRepair.repairMovie(
                    context = imageView.context,
                    provider = provider,
                    database = database,
                    movie = movie,
                ) ?: return@launch

                imageView.post {
                    movie.poster = refreshedMovie.poster
                    movie.banner = refreshedMovie.banner
                    onUpdated(refreshedMovie)
                }
            } finally {
                inFlightRepairs.remove(repairKey)
            }
        }
    }

    fun repairTvShowArtwork(
        imageView: ImageView,
        tvShow: TvShow,
        staleUrl: String,
        onUpdated: (TvShow) -> Unit,
    ) {
        val provider = UserPreferences.currentProvider ?: return
        val repairKey = "${provider.name}|tv|${tvShow.id}|$staleUrl"
        if (!inFlightRepairs.add(repairKey)) return

        repairScope.launch {
            try {
                val database = AppDatabase.getInstance(imageView.context)
                val refreshedTvShow = ArtworkRepair.repairTvShow(
                    context = imageView.context,
                    provider = provider,
                    database = database,
                    tvShow = tvShow,
                ) ?: return@launch

                imageView.post {
                    tvShow.poster = refreshedTvShow.poster
                    tvShow.banner = refreshedTvShow.banner
                    onUpdated(refreshedTvShow)
                }
            } finally {
                inFlightRepairs.remove(repairKey)
            }
        }
    }

    fun repairEpisodeArtwork(
        imageView: ImageView,
        episode: Episode,
        staleUrl: String,
        onUpdated: (Episode) -> Unit,
    ) {
        val tvShow = episode.tvShow ?: return
        val provider = UserPreferences.currentProvider ?: return
        val repairKey = "${provider.name}|episode|${episode.id}|$staleUrl"
        if (!inFlightRepairs.add(repairKey)) return

        repairScope.launch {
            try {
                val database = AppDatabase.getInstance(imageView.context)
                val refreshedTvShow = ArtworkRepair.repairTvShow(
                    context = imageView.context,
                    provider = provider,
                    database = database,
                    tvShow = tvShow,
                ) ?: return@launch

                val refreshedEpisode = database.episodeDao().getById(episode.id) ?: return@launch

                imageView.post {
                    episode.poster = refreshedEpisode.poster
                    onUpdated(refreshedEpisode)
                }
            } finally {
                inFlightRepairs.remove(repairKey)
            }
        }
    }

    fun repairPersonArtwork(
        imageView: ImageView,
        person: People,
        staleUrl: String,
        onUpdated: (People) -> Unit,
    ) {
        val repairKey = "person|${person.id}|$staleUrl"
        if (!inFlightRepairs.add(repairKey)) return

        repairScope.launch {
            try {
                val refreshedPerson = ArtworkRepair.repairPerson(
                    context = imageView.context,
                    person = person,
                ) ?: return@launch

                imageView.post {
                    person.image = refreshedPerson.image
                    onUpdated(refreshedPerson)
                }
            } finally {
                inFlightRepairs.remove(repairKey)
            }
        }
    }

    fun repairSportMatchArtwork(
        imageView: ImageView,
        match: SportMatch,
        staleUrl: String,
        onUpdated: (SportMatch) -> Unit,
    ) {
        val repairKey = "sport|${match.id}|$staleUrl"
        if (!inFlightRepairs.add(repairKey)) return

        repairScope.launch {
            try {
                val refreshedMatch = ArtworkRepair.repairSportMatch(
                    context = imageView.context,
                    match = match,
                ) ?: return@launch

                imageView.post {
                    // Update the match object in place if needed, or rely on the callback
                    onUpdated(refreshedMatch)
                }
            } finally {
                inFlightRepairs.remove(repairKey)
            }
        }
    }
}

private fun ImageView.loadRecoverableArtwork(
    initialUrl: String?,
    configure: RequestBuilder<Drawable>.() -> RequestBuilder<Drawable>,
    onRepair: (staleUrl: String, onUpdated: (String) -> Unit) -> Unit,
) {
    var hasRequestedRepairForBlankUrl = false

    fun submit(url: String?) {
        val requestedUrl = url
        val activity = context?.toActivity()
        if (activity != null && (activity.isDestroyed || activity.isFinishing)) return

        if (requestedUrl.isNullOrBlank() && !hasRequestedRepairForBlankUrl) {
            hasRequestedRepairForBlankUrl = true
            onRepair("") { refreshedUrl ->
                if (!isAttachedToWindow || refreshedUrl.isBlank()) return@onRepair
                submit(refreshedUrl)
            }
        }

        try {
            configure(Glide.with(this).load(requestedUrl))
                .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean,
                ): Boolean {
                    if (!ArtworkRepairCoordinator.shouldRepair(requestedUrl, e)) {
                        return false
                    }

                    onRepair(requestedUrl.orEmpty()) { refreshedUrl ->
                        if (!isAttachedToWindow) return@onRepair
                        submit(refreshedUrl)
                    }
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ) = false
            })
            .into(this)
        } catch (e: Exception) {
            android.util.Log.w("ArtworkLoader", "Glide load skipped: ${e.message}")
        }
    }

    submit(initialUrl)
}

fun ImageView.loadMoviePoster(
    movie: Movie,
    configure: RequestBuilder<Drawable>.() -> RequestBuilder<Drawable> = { this },
) {
    loadRecoverableArtwork(movie.poster, configure) { staleUrl, onUpdated ->
        ArtworkRepairCoordinator.repairMovieArtwork(this, movie, staleUrl) { refreshedMovie ->
            val refreshedUrl = refreshedMovie.poster
            if (!refreshedUrl.isNullOrBlank() && refreshedUrl != staleUrl) {
                onUpdated(refreshedUrl)
            }
        }
    }
}

fun ImageView.loadMovieBanner(
    movie: Movie,
    configure: RequestBuilder<Drawable>.() -> RequestBuilder<Drawable> = { this },
) {
    loadRecoverableArtwork(movie.banner, configure) { staleUrl, onUpdated ->
        ArtworkRepairCoordinator.repairMovieArtwork(this, movie, staleUrl) { refreshedMovie ->
            val refreshedUrl = refreshedMovie.banner
            if (!refreshedUrl.isNullOrBlank() && refreshedUrl != staleUrl) {
                onUpdated(refreshedUrl)
            }
        }
    }
}

fun ImageView.loadTvShowPoster(
    tvShow: TvShow,
    configure: RequestBuilder<Drawable>.() -> RequestBuilder<Drawable> = { this },
) {
    loadRecoverableArtwork(tvShow.poster, configure) { staleUrl, onUpdated ->
        ArtworkRepairCoordinator.repairTvShowArtwork(this, tvShow, staleUrl) { refreshedTvShow ->
            val refreshedUrl = refreshedTvShow.poster
            if (!refreshedUrl.isNullOrBlank() && refreshedUrl != staleUrl) {
                onUpdated(refreshedUrl)
            }
        }
    }
}

fun ImageView.loadTvShowBanner(
    tvShow: TvShow,
    configure: RequestBuilder<Drawable>.() -> RequestBuilder<Drawable> = { this },
) {
    loadRecoverableArtwork(tvShow.banner, configure) { staleUrl, onUpdated ->
        ArtworkRepairCoordinator.repairTvShowArtwork(this, tvShow, staleUrl) { refreshedTvShow ->
            val refreshedUrl = refreshedTvShow.banner
            if (!refreshedUrl.isNullOrBlank() && refreshedUrl != staleUrl) {
                onUpdated(refreshedUrl)
            }
        }
    }
}

fun ImageView.loadTvShowCardArtwork(
    tvShow: TvShow,
    configure: RequestBuilder<Drawable>.() -> RequestBuilder<Drawable> = { this },
) {
    loadRecoverableArtwork(tvShow.poster ?: tvShow.banner, configure) { staleUrl, onUpdated ->
        ArtworkRepairCoordinator.repairTvShowArtwork(this, tvShow, staleUrl) { refreshedTvShow ->
            val refreshedUrl = refreshedTvShow.poster ?: refreshedTvShow.banner
            if (!refreshedUrl.isNullOrBlank() && refreshedUrl != staleUrl) {
                onUpdated(refreshedUrl)
            }
        }
    }
}

fun ImageView.loadEpisodePoster(
    episode: Episode,
    configure: RequestBuilder<Drawable>.() -> RequestBuilder<Drawable> = { this },
) {
    loadRecoverableArtwork(episode.poster, configure) { staleUrl, onUpdated ->
        ArtworkRepairCoordinator.repairEpisodeArtwork(this, episode, staleUrl) { refreshedEpisode ->
            val refreshedUrl = refreshedEpisode.poster
            if (!refreshedUrl.isNullOrBlank() && refreshedUrl != staleUrl) {
                onUpdated(refreshedUrl)
            }
        }
    }
}

fun ImageView.loadPersonProfile(
    person: People,
    configure: RequestBuilder<Drawable>.() -> RequestBuilder<Drawable> = { this },
) {
    loadRecoverableArtwork(person.image, configure) { staleUrl, onUpdated ->
        ArtworkRepairCoordinator.repairPersonArtwork(this, person, staleUrl) { refreshedPerson ->
            val refreshedUrl = refreshedPerson.image
            if (!refreshedUrl.isNullOrBlank() && refreshedUrl != staleUrl) {
                onUpdated(refreshedUrl)
            }
        }
    }
}

fun ImageView.loadSportMatchPoster(
    match: SportMatch,
    configure: RequestBuilder<Drawable>.() -> RequestBuilder<Drawable> = { this },
) {
    loadRecoverableArtwork(match.poster, configure) { staleUrl, onUpdated ->
        ArtworkRepairCoordinator.repairSportMatchArtwork(this, match, staleUrl) { refreshedMatch ->
            val refreshedUrl = refreshedMatch.poster
            if (!refreshedUrl.isNullOrBlank() && refreshedUrl != staleUrl) {
                onUpdated(refreshedUrl)
            }
        }
    }
}
