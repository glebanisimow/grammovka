package app.grammovka.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.grammovka.GrammovkaApp
import app.grammovka.MainActivity
import app.grammovka.domain.model.Fmt
import java.time.LocalDate

/** What the widget shows; null budget means the profile or a weigh-in is missing. */
data class WidgetData(val remaining: Double?, val eatenKcal: Double, val protein: Double, val proteinTarget: Double?)

/**
 * Home-screen widget (spec, phase 2): remaining budget, protein, «добавить» and «сканер».
 * https://developer.android.com/develop/ui/compose/glance/create-app-widget
 */
class GrammovkaWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = load(context)
        provideContent { Content(context, data) }
    }

    private suspend fun load(context: Context): WidgetData {
        val c = (context.applicationContext as GrammovkaApp).container
        val today = LocalDate.now()
        val totals = c.database.logDao().totals(today, today).firstOrNull()
        val day = c.modelRepository.recompute(c.settingsStore.current().profile, today)?.day(today)
        val eaten = totals?.kcal ?: 0.0
        return WidgetData(day?.let { it.balance(eaten).left }, eaten, totals?.protein ?: 0.0, day?.snapshot?.proteinTarget)
    }

    @androidx.compose.runtime.Composable
    private fun Content(context: Context, d: WidgetData) {
        val bg = ColorProvider(day = Color(0xFFFAF8F2), night = Color(0xFF14130F))
        val text = ColorProvider(day = Color(0xFF0A0A09), night = Color(0xFFF4F1E8))
        val muted = ColorProvider(day = Color(0xFF5E5A50), night = Color(0xFF8C877B))
        val accent = ColorProvider(day = Color(0xFF9A5B00), night = Color(0xFFFFAA1D))
        val danger = ColorProvider(day = Color(0xFFB42318), night = Color(0xFFFF6B5A))
        val button = ColorProvider(day = Color(0xFFFFAA1D), night = Color(0xFFFFAA1D))
        val onButton = ColorProvider(day = Color(0xFF0A0A09), night = Color(0xFF0A0A09))

        fun open(action: String?) = actionStartActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply { if (action != null) putExtra(EXTRA_ACTION, action) },
        )

        Column(
            GlanceModifier.fillMaxSize().background(bg).cornerRadius(6.dp).padding(12.dp).clickable(open(null)),
        ) {
            if (d.remaining == null) {
                Text("Protoptera eat", style = TextStyle(color = muted, fontSize = 12.sp))
                Text("Съедено ${Fmt.kcal(d.eatenKcal)} ккал", style = TextStyle(color = text, fontSize = 20.sp, fontWeight = FontWeight.Bold))
                Text("Бюджет появится после профиля и взвешивания", style = TextStyle(color = muted, fontSize = 12.sp))
            } else {
                val over = d.remaining < 0
                Text(if (over) "Сверх бюджета" else "Осталось", style = TextStyle(color = muted, fontSize = 12.sp))
                Text(
                    "${Fmt.kcal(kotlin.math.abs(d.remaining))} ккал",
                    style = TextStyle(color = if (over) danger else accent, fontSize = 26.sp, fontWeight = FontWeight.Bold),
                )
                Text(
                    "Белок ${Fmt.kcal(d.protein)}" + (d.proteinTarget?.let { " из ${Fmt.kcal(it)} г" } ?: " г"),
                    style = TextStyle(color = text, fontSize = 13.sp),
                )
            }
            Spacer(GlanceModifier.defaultWeight())
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Chip("Добавить", button, onButton, open(ACTION_ADD), GlanceModifier.defaultWeight())
                Spacer(GlanceModifier.width(8.dp))
                Chip("Сканер", button, onButton, open(ACTION_SCAN), GlanceModifier.defaultWeight())
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Chip(
        label: String,
        bg: androidx.glance.unit.ColorProvider,
        fg: androidx.glance.unit.ColorProvider,
        action: androidx.glance.action.Action,
        modifier: GlanceModifier,
    ) {
        Box(
            modifier.height(36.dp).background(bg).cornerRadius(4.dp).clickable(action),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = TextStyle(color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium))
        }
    }

    companion object {
        const val EXTRA_ACTION = "widget_action"
        const val ACTION_ADD = "add"
        const val ACTION_SCAN = "scan"

        suspend fun refresh(context: Context) = GrammovkaWidget().updateAll(context)
    }
}

class GrammovkaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GrammovkaWidget()
}
