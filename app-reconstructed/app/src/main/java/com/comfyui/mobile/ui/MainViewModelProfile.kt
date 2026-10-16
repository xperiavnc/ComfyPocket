package com.comfyui.mobile.ui

object MainViewModelProfile {

    fun updateUserProfile(
        vm: MainViewModel,
        nickname: String,
        bio: String,
        qq: String
    ) {
        val trimmedNick = nickname.trim()
        val trimmedBio = bio.trim()
        val trimmedQq = qq.trim()

        vm._userNickname.value = trimmedNick
        vm._userBio.value = trimmedBio
        vm._userQq.value = trimmedQq

        vm.prefs.edit()
            .putString("profile_nickname", trimmedNick)
            .putString("profile_bio", trimmedBio)
            .putString("profile_qq", trimmedQq)
            .apply()
    }
}
