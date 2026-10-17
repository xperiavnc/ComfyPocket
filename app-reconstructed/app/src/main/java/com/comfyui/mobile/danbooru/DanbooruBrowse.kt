package com.comfyui.mobile.danbooru

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

enum class DanbooruSort(val title: String, val token: String) {
    LATEST("最新", "order:id_desc"),
    HOT("热度", "order:rank"),
    SCORE("评分", "order:score"),
    FAVORITES("收藏", "order:favcount"),
    RANKING("排行榜", "order:score"),
    CUSTOM("自定义", "")
}

/** Match Danbooru's popular controller: site dates, Monday weeks and calendar months. */
enum class RankingPeriod(val title: String) {
    DAY("日榜"), WEEK("周榜"), MONTH("月榜");

    fun start(date: LocalDate): LocalDate = when (this) {
        DAY -> date
        WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        MONTH -> date.withDayOfMonth(1)
    }

    fun shift(date: LocalDate, amount: Long): LocalDate = when (this) {
        DAY -> start(date).plusDays(amount)
        WEEK -> start(date).plusWeeks(amount)
        MONTH -> start(date).plusMonths(amount)
    }

    fun dateTag(date: LocalDate): String = "date:${start(date)}..${shift(date, 1)}"

    fun label(date: LocalDate): String = when (this) {
        DAY -> start(date).toString()
        WEEK -> "${start(date)} — ${shift(date, 1).minusDays(1)}"
        MONTH -> "${start(date).year}-${start(date).monthValue.toString().padStart(2, '0')}"
    }
}

fun danbooruToday(): LocalDate = LocalDate.now(ZoneId.of("America/New_York"))
