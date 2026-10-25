package com.vara.mediatracker.ui.m3e

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * The first thing a new install shows.
 *
 * The app opens on an empty library, and the two things a person needs to know are not visible from
 * there: that titles come from Search rather than being found automatically, and that the phone and
 * the Mac find each other by pasting one key. Both were only discoverable by exploring Settings.
 *
 * Two actions, and both are honest: go and find something, or dismiss and look around. It is a
 * dialog rather than a full-screen flow because the app underneath is already the answer to "what
 * is this" — hiding it behind three slides would be a worse introduction than showing it.
 */
@Composable
fun WelcomeDialog(
    onFindSomething: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = M3EColor.card(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(M3ESpacing.page)
        ) {
            Column(
                modifier = Modifier.padding(M3ESpacing.sectionGap),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap)
            ) {
                Text(
                    text = "Welcome to MediaTracker",
                    style = M3EType.sectionTitle(),
                    color = M3EColor.text(),
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Your films and shows, tracked by you — what you're watching, what's next, and what you thought of it.",
                    style = M3EType.rowSupporting(),
                    color = M3EColor.textMuted(),
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(M3ESpacing.tightGap))

                // The pairing line, because it is the one thing nobody guesses.
                Text(
                    text = "Got the Mac app too? Open Settings → Sync on both and paste the same key. Everything you mark here shows up there.",
                    style = M3EType.rowSupporting(),
                    color = M3EColor.textMuted(),
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(M3ESpacing.cardGap))

                Button(
                    onClick = onFindSomething,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Find something to watch")
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("I'll look around", style = M3EType.action())
                }
            }
        }
    }
}
