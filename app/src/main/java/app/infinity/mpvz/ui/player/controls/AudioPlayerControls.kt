                contentDescription = "Playlist",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp),
              )
            }
          }
        }
      }
    }

    val isTabletPortrait = isPortrait && (isTablet || configuration.screenWidthDp >= 600)

    if (isPortrait) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .clickable(
            enabled = showInPlaceLyrics,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
          ) { resetInactivityTimer() },
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        if (edgeToEdgeVisualizer && !isStandbyActive) {
          // Keep the visualizer as the full-width/full-height upper layer, including behind
          // the header. The lower metadata, seekbar and playback controls remain untouched.
          Box(
            modifier = Modifier
              .weight(1f)
              .fillMaxWidth()
              .clipToBounds(),
          ) {
            centerVisualizerView(
              Modifier.fillMaxSize(),
              false,
            )
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              headerBar()
              losslessBadge()
            }
          }
        } else {
          androidx.compose.animation.AnimatedVisibility(
            visible = !isStandbyActive,
            enter = fadeIn(animationSpec = tween(300)) + androidx.compose.animation.expandVertically(animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(300)) + androidx.compose.animation.shrinkVertically(animationSpec = tween(300)),
          ) {
            Column(
              modifier = Modifier.padding(horizontal = controlsSidePadding),
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              headerBar()
              losslessBadge()
              Spacer(modifier = Modifier.height(16.dp))
            }
          }

          centerVisualizerView(Modifier.weight(1f).fillMaxWidth(), false)
        }
        if (isStandbyActive) {
          seekbarView()
        }

        androidx.compose.animation.AnimatedVisibility(
          visible = !isStandbyActive,
          enter = fadeIn(animationSpec = tween(300)) + androidx.compose.animation.expandVertically(animationSpec = tween(300)),
          exit = fadeOut(animationSpec = tween(300)) + androidx.compose.animation.shrinkVertically(animationSpec = tween(300)),
        ) {
          Surface(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 12.dp),
            shape = RoundedCornerShape(28.dp),
            color = Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
          ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
              .padding(horizontal = 14.dp, vertical = 10.dp)
              .padding(horizontal = controlsSidePadding),
          ) {
            Spacer(modifier = Modifier.height(16.dp))
            trackMetadataView()
            Spacer(modifier = Modifier.height(16.dp))
            seekbarView()
            Spacer(modifier = Modifier.height(16.dp))
            playbackControlsRow()
            Spacer(modifier = Modifier.height(24.dp))
            bottomActionRow()
          }
          }
        }
      }
    } else if (false && isTabletLandscape) {