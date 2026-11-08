package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ConsoleBackground = Color(0xFF0D1117)
private val ConsoleSurface = Color(0xFF161B22)
private val ConsoleBorder = Color(0xFF30363D)
private val ColorCmd = Color(0xFF58A6FF)
private val ColorText = Color(0xFFC9D1D9)

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ConsoleBackground)
    ) {
        // Tab Navigation Bar (3 Tabs: Player, Camera, Terminal Logs)
        TabRow(
            selectedTabIndex = selectedTabIndex,
            containerColor = ConsoleSurface,
            contentColor = ColorCmd,
            indicator = { tabPositions ->
                if (selectedTabIndex < tabPositions.size) {
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                        color = ColorCmd,
                        height = 3.dp
                    )
                }
            },
            divider = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(ConsoleBorder)
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            // Tab 0: GStreamer Video Player
            var tab0Focused by remember { mutableStateOf(false) }
            Tab(
                selected = selectedTabIndex == 0,
                onClick = { selectedTabIndex = 0 },
                text = {
                    Text(
                        text = "Player",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (selectedTabIndex == 0) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 12.sp,
                        color = if (selectedTabIndex == 0) ColorCmd else ColorText
                    )
                },
                icon = {
                    Icon(
                        imageVector = Icons.Default.PlayCircle,
                        contentDescription = "Player Tab",
                        tint = if (selectedTabIndex == 0) ColorCmd else ColorText
                    )
                },
                modifier = Modifier
                    .testTag("tab_player")
                    .onFocusChanged { tab0Focused = it.isFocused }
                    .border(
                        width = if (tab0Focused) 2.dp else 0.dp,
                        color = if (tab0Focused) Color.White else Color.Transparent
                    )
                    .focusable()
            )

            // Tab 1: Camera Viewfinder
            var tab1Focused by remember { mutableStateOf(false) }
            Tab(
                selected = selectedTabIndex == 1,
                onClick = { selectedTabIndex = 1 },
                text = {
                    Text(
                        text = "Camera",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (selectedTabIndex == 1) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 12.sp,
                        color = if (selectedTabIndex == 1) ColorCmd else ColorText
                    )
                },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = "Camera Viewfinder Tab",
                        tint = if (selectedTabIndex == 1) ColorCmd else ColorText
                    )
                },
                modifier = Modifier
                    .testTag("tab_camera_viewfinder")
                    .onFocusChanged { tab1Focused = it.isFocused }
                    .border(
                        width = if (tab1Focused) 2.dp else 0.dp,
                        color = if (tab1Focused) Color.White else Color.Transparent
                    )
                    .focusable()
            )

            // Tab 2: Terminal Logs
            var tab2Focused by remember { mutableStateOf(false) }
            Tab(
                selected = selectedTabIndex == 2,
                onClick = { selectedTabIndex = 2 },
                text = {
                    Text(
                        text = "Terminal Logs",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (selectedTabIndex == 2) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 12.sp,
                        color = if (selectedTabIndex == 2) ColorCmd else ColorText
                    )
                },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = "Terminal Logs Tab",
                        tint = if (selectedTabIndex == 2) ColorCmd else ColorText
                    )
                },
                modifier = Modifier
                    .testTag("tab_terminal_logs")
                    .onFocusChanged { tab2Focused = it.isFocused }
                    .border(
                        width = if (tab2Focused) 2.dp else 0.dp,
                        color = if (tab2Focused) Color.White else Color.Transparent
                    )
                    .focusable()
            )
        }

        // Active Tab Screen Content
        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        ) {
            when (selectedTabIndex) {
                0 -> VideoPipelineSection()
                1 -> CameraViewfinderSection()
                2 -> TerminalLogViewer()
            }
        }
    }
}
