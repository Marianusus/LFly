package org.sonora.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.mlkit.nl.translate.TranslateLanguage
import org.sonora.android.R
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.SonoraSurfaceCard
import org.sonora.android.ui.theme.SonoraTextPrimary
import org.sonora.android.ui.theme.SonoraTextSecondary
import java.util.Locale

/**
 * All languages supported by the on-device ML Kit translator (BCP-47 code + localized label),
 * sorted alphabetically. Includes Romanian and every other ML Kit target.
 */
fun mlKitTargetLanguages(): List<Pair<String, String>> =
    TranslateLanguage.getAllLanguages()
        .map { code -> code to translationLanguageLabel(code) }
        .sortedBy { it.second.lowercase(Locale.getDefault()) }

/** Localized display name for a BCP-47 language code (e.g. "ro" -> "Rumeno"). */
fun translationLanguageLabel(code: String): String {
    if (code.isBlank()) return "—"
    val locale = Locale.forLanguageTag(code)
    val name = locale.getDisplayName(Locale.getDefault())
    if (name.isBlank() || name.equals(code, ignoreCase = true)) return code.uppercase(Locale.getDefault())
    return name.replaceFirstChar { char ->
        if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
    }
}

/** Scrollable picker with every ML Kit language, used by both the lyrics menu and Settings. */
@Composable
fun TranslationLanguageDialog(
    current: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val languages = remember { mlKitTargetLanguages() }
    val accent = LocalBackgroundScheme.current.accent

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SonoraSurfaceCard,
        title = {
            Text(
                text = stringResource(R.string.translation_lang_dialog_title),
                style = MaterialTheme.typography.titleMedium,
                color = SonoraTextPrimary
            )
        },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(languages, key = { it.first }) { (code, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelect(code) }
                            .padding(horizontal = 8.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (code == current) accent else SonoraTextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        if (code == current) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = accent
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.translation_lang_dialog_close), color = SonoraTextSecondary)
            }
        }
    )
}
