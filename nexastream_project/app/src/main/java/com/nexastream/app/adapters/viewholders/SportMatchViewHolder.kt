package com.nexastream.app.adapters.viewholders

import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import androidx.navigation.findNavController
import com.nexastream.app.R
import com.nexastream.app.databinding.ItemSportMatchMobileBinding
import com.nexastream.app.databinding.ItemSportMatchTvBinding
import com.nexastream.app.models.SportMatch
import com.nexastream.app.models.Video
import com.nexastream.app.providers.AkSportsLiveProvider
import com.nexastream.app.providers.NexaHomeProvider
import com.nexastream.app.utils.UserPreferences
import com.nexastream.app.utils.loadSportMatchPoster

class SportMatchViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(_binding.root) {

    private lateinit var match: SportMatch

    fun bind(match: SportMatch) {
        this.match = match
        when (_binding) {
            is ItemSportMatchMobileBinding -> displayMobileItem(_binding)
            is ItemSportMatchTvBinding -> displayTvItem(_binding)
        }
    }

    private fun displayMobileItem(binding: ItemSportMatchMobileBinding) {
        val processedMatch = parseLiveDetails(match)
        binding.apply {
            root.setOnClickListener {
                openMatch(it, processedMatch)
            }
            tvMatchLeague.text = processedMatch.league
            tvHomeTeam.text = processedMatch.homeTeam
            tvAwayTeam.text = processedMatch.awayTeam
            tvMatchScore.text = processedMatch.score
            tvMatchTime.text = processedMatch.time

            setupStatusBadge(tvMatchStatus, processedMatch.status)

            ivMatchPoster.loadSportMatchPoster(processedMatch) {
                placeholder(R.drawable.glide_fallback_cover)
                error(R.drawable.glide_fallback_cover)
            }
        }
    }

    private fun displayTvItem(binding: ItemSportMatchTvBinding) {
        val processedMatch = parseLiveDetails(match)
        binding.apply {
            root.setOnClickListener {
                openMatch(it, processedMatch)
            }
            tvMatchLeague.text = processedMatch.league
            tvHomeTeam.text = processedMatch.homeTeam
            tvAwayTeam.text = processedMatch.awayTeam
            tvMatchScore.text = processedMatch.score
            tvMatchTime.text = processedMatch.time

            setupStatusBadge(tvMatchStatus, processedMatch.status)

            ivMatchPoster.loadSportMatchPoster(processedMatch) {
                placeholder(R.drawable.glide_fallback_cover)
                error(R.drawable.glide_fallback_cover)
            }
        }
    }

    private fun openMatch(view: android.view.View, match: SportMatch) {
        val playbackId = if (UserPreferences.currentProvider == NexaHomeProvider) {
            if (UserPreferences.previousProviderToRestore == null) {
                UserPreferences.previousProviderToRestore = UserPreferences.currentProvider
            }
            UserPreferences.currentProvider = AkSportsLiveProvider
            AkSportsLiveProvider.playbackId(match)
        } else {
            match.id
        }

        view.findNavController().navigate(
            R.id.action_global_player,
            bundleOf(
                "id" to playbackId,
                "title" to match.title,
                "subtitle" to "${match.league} - ${match.time}",
                "videoType" to Video.Type.Movie(
                    id = playbackId,
                    title = match.title,
                    releaseDate = "",
                    poster = match.poster ?: "",
                    imdbId = null
                )
            )
        )
    }

    private fun setupStatusBadge(textView: TextView, status: String) {
        val upper = status.uppercase().trim()
        when {
            upper == "LIVE" || upper.contains("LIVE") -> {
                textView.text = "LIVE"
                textView.setBackgroundResource(R.drawable.bg_live_badge)
                textView.isVisible = true
            }
            upper == "UPCOMING" || upper == "NS" || upper == "SCHEDULED" -> {
                textView.text = "UPCOMING"
                textView.setBackgroundColor(android.graphics.Color.parseColor("#0288D1"))
                textView.isVisible = true
            }
            upper == "HT" || upper == "HALF TIME" -> {
                textView.text = "HALF TIME"
                textView.setBackgroundColor(android.graphics.Color.parseColor("#1565C0"))
                textView.isVisible = true
            }
            upper == "FIN" || upper == "FINISHED" || upper == "FT" -> {
                textView.text = "FINISHED"
                textView.setBackgroundColor(android.graphics.Color.parseColor("#424242"))
                textView.isVisible = true
            }
            upper == "PENS" || upper == "PENALTIES" -> {
                textView.text = "PENALTIES"
                textView.setBackgroundColor(android.graphics.Color.parseColor("#D81B60"))
                textView.isVisible = true
            }
            status.isNotBlank() -> {
                textView.text = upper
                textView.setBackgroundResource(R.drawable.bg_live_badge)
                textView.isVisible = true
            }
            else -> {
                textView.isVisible = false
            }
        }
    }

    private fun parseLiveDetails(match: SportMatch): SportMatch {
        val title = match.title
        var home = match.homeTeam
        var away = match.awayTeam
        var score = match.score

        if (home.isBlank() || away.isBlank() || home.equals(title, ignoreCase = true)) {
            val vsSplit = title.split(Regex("""\s+vs\.?\s+|\s+-\s+""", RegexOption.IGNORE_CASE))
            if (vsSplit.size >= 2) {
                home = vsSplit[0].trim()
                away = vsSplit[1].trim()
            }
        }

        val scoreRegex = Regex("""(\d+)\s*-\s*(\d+)""")
        val scoreMatch = scoreRegex.find(title)
        if (scoreMatch != null && (score == "vs" || score.isBlank())) {
            val homeScore = scoreMatch.groupValues[1]
            val awayScore = scoreMatch.groupValues[2]
            score = "$homeScore - $awayScore"
        }

        var league = match.league
        if (league.isBlank() || league.equals("sports", ignoreCase = true)) {
            league = match.sport?.uppercase() ?: "LIVE SPORTS"
        }

        return match.copy(
            homeTeam = home,
            awayTeam = away,
            score = score,
            league = league
        )
    }
}
