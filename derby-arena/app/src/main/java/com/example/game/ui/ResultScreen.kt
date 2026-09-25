package com.example.game.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.game.GameViewModel

/** Match result: VICTORY / ELIMINATED with placement, kills, time, damage, scrap, stars. */
@Composable
fun ResultScreen(viewModel: GameViewModel, won: Boolean) {
    val result by viewModel.lastResult.collectAsStateWithLifecycle()
    val level by viewModel.currentLevel.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.background(Color(0xF0121212)).border(2.dp, if (won) Color(0xFFD84315) else Color(0xFFD32F2F)).padding(26.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(if (won) "VICTORY" else "ELIMINATED", color = if (won) Color(0xFFD84315) else Color(0xFFD32F2F), fontSize = 40.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp, modifier = Modifier.testTag("result_title"))
            Text("STAGE $level · LAST RIG STANDING", color = Color(0xFF888888), fontSize = 11.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(16.dp))
            result?.let { r ->
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    ResultStat("PLACEMENT", "#${r.placement} / ${r.totalCombatants}")
                    ResultStat("KILLS", "${r.kills}")
                    ResultStat("TIME", "%02d:%02d".format(r.timeSeconds / 60, r.timeSeconds % 60))
                    ResultStat("DAMAGE TAKEN", "${r.damageTaken}")
                    ResultStat("SCRAP", "+${r.scrapEarned}")
                }
                if (won) {
                    Spacer(Modifier.height(10.dp))
                    Text((1..3).joinToString(" ") { if (it <= r.stars) "★" else "☆" }, color = Color(0xFFFFB300), fontSize = 28.sp, modifier = Modifier.testTag("result_stars"))
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ResultButton(if (won) "NEXT STAGE" else "RETRY", Color(0xFFD84315), "result_primary_button") { if (won) viewModel.nextLevel() else viewModel.restartStage() }
                ResultButton("GARAGE", Color(0xFF8D6E63), "result_garage_button") { viewModel.openGarage() }
                ResultButton("MENU", Color(0xFF555555), "result_menu_button") { viewModel.goToMenu() }
            }
        }
    }
}

@Composable
private fun ResultStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color(0xFF888888), fontSize = 9.sp, letterSpacing = 2.sp)
        Text(value, color = Color(0xFFF5F5F5), fontSize = 20.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ResultButton(label: String, color: Color, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.background(color).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp).testTag(tag),
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp) }
}
