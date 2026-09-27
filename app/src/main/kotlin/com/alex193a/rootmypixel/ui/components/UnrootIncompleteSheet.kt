package com.alex193a.rootmypixel.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.alex193a.rootmypixel.R
import com.alex193a.rootmypixel.domain.model.UnrootWarningUi

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnrootIncompleteSheet(
    warning: UnrootWarningUi,
    onDismiss: () -> Unit,
    onRebootAnyway: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(R.string.unroot_incomplete_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.unroot_incomplete_message),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = warning.failedItemsText,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.unroot_temporary_root_reboot_note),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onRebootAnyway,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_reboot_anyway))
            }
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_do_not_reboot))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
