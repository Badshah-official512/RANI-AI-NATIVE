package com.badshah222.rani.ai.core

import com.badshah222.rani.ai.BuildConfig

object Config {
    const val SUPABASE_URL: String = BuildConfig.SUPABASE_URL
    const val SUPABASE_KEY: String = BuildConfig.SUPABASE_PUBLISHABLE_KEY // public by design; RLS protects data
    const val FUNCTIONS_REGION: String = BuildConfig.FUNCTIONS_REGION

    // Official social links (exact values supplied by the owner)
    const val TIKTOK = "https://www.tiktok.com/@zain_ff45"
    const val YOUTUBE = "https://www.youtube.com/@Badshahofficial-222"
    const val FACEBOOK = "https://www.facebook.com/share/1Jgyv3r7vG/"
    const val INSTAGRAM = "https://www.instagram.com/badshahofficial222"
    const val WHATSAPP = "https://wa.me/923251787969"
}
