package com.nexastream.app.adapters.viewholders

import android.content.Intent
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.nexastream.app.R
import com.nexastream.app.databinding.ItemProviderMobileBinding
import com.nexastream.app.databinding.ItemProviderTvBinding
import com.nexastream.app.models.Provider
import com.nexastream.app.utils.UserPreferences
import com.nexastream.app.utils.toActivity
import java.util.Locale

class ProviderViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    private val context = itemView.context
    private lateinit var provider: Provider

    fun bind(provider: Provider) {
        this.provider = provider

        when (_binding) {
            is ItemProviderMobileBinding -> displayMobileItem(_binding)
            is ItemProviderTvBinding -> displayTvItem(_binding)
        }
    }


    private fun displayMobileItem(binding: ItemProviderMobileBinding) {
        binding.root.apply {
            setOnClickListener {
                if (UserPreferences.currentProvider.name == provider.provider.name || UserPreferences.currentProvider.name == provider.name) return@setOnClickListener

                UserPreferences.setCurrentProvider(provider.provider, notify = false)
                context.toActivity()?.apply {
                    startActivity(
                        Intent(this, this::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }
                    )
                    finish()
                }
            }
            setOnLongClickListener {
                toggleFavorite(provider)
                binding.ivProviderFavorite.visibility = if (provider.isFavorite) android.view.View.VISIBLE else android.view.View.GONE
                true
            }
        }
        
        binding.ivProviderFavorite.visibility = if (provider.isFavorite) android.view.View.VISIBLE else android.view.View.GONE

        Glide.with(context)
            .load(provider.logo.takeIf { it.isNotEmpty() }
                ?: R.drawable.ic_provider_default_logo)
            .error(R.drawable.ic_provider_default_logo)
            .fitCenter()
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(binding.ivProviderLogo)

        binding.tvProviderName.text = provider.name

        binding.tvProviderLanguage.text = Locale.forLanguageTag(provider.language)
            .let { it.getDisplayLanguage(it) }
            .replaceFirstChar { it.titlecase() }
    }

    private fun displayTvItem(binding: ItemProviderTvBinding) {
        binding.root.apply {
            isFocusable = true
            setOnClickListener {
                if (UserPreferences.currentProvider.name == provider.provider.name || UserPreferences.currentProvider.name == provider.name) return@setOnClickListener

                UserPreferences.setCurrentProvider(provider.provider, notify = false)
                context.toActivity()?.apply {
                    startActivity(
                        Intent(this, this::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }
                    )
                    finish()
                }
            }
            setOnLongClickListener {
                toggleFavorite(provider)
                binding.ivProviderFavorite.visibility = if (provider.isFavorite) android.view.View.VISIBLE else android.view.View.GONE
                true
            }
            setOnFocusChangeListener { _, hasFocus ->
                val animation = if (hasFocus) {
                    android.view.animation.AnimationUtils.loadAnimation(context, R.anim.zoom_in)
                } else {
                    android.view.animation.AnimationUtils.loadAnimation(context, R.anim.zoom_out)
                }
                startAnimation(animation)
                animation.fillAfter = true
            }
        }
        
        binding.ivProviderFavorite.visibility = if (provider.isFavorite) android.view.View.VISIBLE else android.view.View.GONE

        Glide.with(context)
            .load(provider.logo.takeIf { it.isNotEmpty() }
                ?: R.drawable.ic_provider_default_logo)
            .error(R.drawable.ic_provider_default_logo)
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(binding.ivProviderLogo)

        binding.tvProviderName.text = provider.name

        binding.tvProviderLanguage.text = Locale.forLanguageTag(provider.language)
            .let { it.getDisplayLanguage(it) }
            .replaceFirstChar { it.titlecase() }
    }
    
    private fun toggleFavorite(provider: Provider) {
        provider.isFavorite = !provider.isFavorite
        val favorites = UserPreferences.favoriteProviders.toMutableSet()
        if (provider.isFavorite) {
            favorites.add(provider.name)
        } else {
            favorites.remove(provider.name)
        }
        UserPreferences.favoriteProviders = favorites
    }
}
