package com.nexastream.app.fragments.genre

import android.view.View
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class KidsTvFragment : GenreTvFragment() {

    override fun setupSubcategories() {
        binding.hgvSubcategories.visibility = View.GONE
        binding.tvGenreName.visibility = View.GONE
    }
}
