package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.game.GameViewModel
import com.example.game.GameViewModelFactory
import com.example.game.data.DerbyDatabase
import com.example.game.data.DerbyRepository
import com.example.game.ui.DerbyArenaGame
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    // Initialize Room Database, DAO, and Repository
    val database = DerbyDatabase.getDatabase(this)
    val repository = DerbyRepository(database.derbyDao())
    val factory = GameViewModelFactory(application, repository)

    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
          val gameViewModel: GameViewModel = viewModel(factory = factory)
          DerbyArenaGame(
              viewModel = gameViewModel,
              modifier = Modifier.padding(innerPadding)
          )
        }
      }
    }
  }
}

