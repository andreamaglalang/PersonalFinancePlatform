package com.elect3.mobdev.personalfinance.ui.categories

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.Category
import com.elect3.mobdev.personalfinance.ui.components.OwliMockDataChip
import com.elect3.mobdev.personalfinance.ui.auth.owliFieldColors
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliColors
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliShapes
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography
import kotlinx.coroutines.delay

/** Page header: leading action, centred title (heading), optional trailing action. */
@Composable
internal fun CategoryPageHeader(title: String, leading: @Composable () -> Unit, trailing: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp)) {
            Box(Modifier.align(Alignment.CenterStart)) { leading() }
            Text(title, style = LocalOwliTypography.current.bodyStrong, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 88.dp).semantics { heading() })
            Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
        }
    }
}

@Composable
internal fun HeaderTextButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(text, style = LocalOwliTypography.current.label, color = if (enabled) LocalOwliColors.current.accent else Color.Unspecified)
    }
}

@Composable
internal fun HeaderBackButton(onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(painterResource(R.drawable.ic_cat_chevl), contentDescription = stringResource(R.string.cat_back))
    }
}

@Composable
private fun StatusLine(text: String, error: Boolean = false, modifier: Modifier = Modifier) {
    Text(text, style = LocalOwliTypography.current.caption,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

/** Grid columns: 4 normally, 3 when narrow or at large font, 2 at very large font. */
@Composable
private fun gridColumns(maxWidthDp: Float): Int {
    val scale = LocalConfiguration.current.fontScale
    return when { scale >= 1.8f -> 2; scale > 1.3f || maxWidthDp < 340f -> 3; else -> 4 }
}

@Composable
private fun LoadingOrError(state: CategoryCatalogState, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.loading) {
            CircularProgressIndicator(Modifier.size(28.dp))
            StatusLine(stringResource(R.string.cat_loading))
        } else if (state.error != null) {
            StatusLine(stringResource(R.string.cat_error), error = true)
            OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.transactions_retry)) }
        }
    }
}

@Composable
private fun EmptyCategories(title: String, message: String, action: String?, onAction: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(LocalOwliColors.current.tile), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_owli_mark), null, tint = LocalOwliColors.current.tileContent, modifier = Modifier.size(36.dp))
        }
        Text(title, style = LocalOwliTypography.current.bodyStrong, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(message, style = LocalOwliTypography.current.body, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null && onAction != null) OutlinedButton(onClick = onAction, modifier = Modifier.padding(top = 10.dp).heightIn(min = 48.dp)) { Text(action) }
    }
}

private fun typeWord(type: TransactionType) = if (type == TransactionType.Income) R.string.transaction_income else R.string.transaction_expense

// ---------------------------------------------------------------- Choose a category

@Composable
fun CategoryChooserScreen(
    catalog: CategoryCatalogViewModel,
    state: CategoryCatalogState,
    tab: TransactionType,
    selectedId: Int?,
    onTab: (TransactionType) -> Unit,
    onPick: (Category) -> Unit,
    onManage: (() -> Unit)?,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).navigationBarsPadding()) {
        CategoryPageHeader(stringResource(R.string.cat_choose_title),
            leading = { HeaderTextButton(stringResource(R.string.cat_cancel), onCancel) },
            trailing = {
                if (onManage != null) IconButton(onClick = onManage, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_cat_sliders), contentDescription = stringResource(R.string.cat_manage_action))
                }
            })
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
            if (catalog.demo) OwliMockDataChip(Modifier.padding(bottom = 10.dp), text = stringResource(R.string.auth_demo_title))
            CategoryTypeTabs(tab, onTab)
        }
        HorizontalDivider(color = LocalOwliColors.current.hairline)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.cat_choose_overline, stringResource(typeWord(tab))),
                style = LocalOwliTypography.current.overline, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp).semantics { heading() })
            if (!state.loaded) { LoadingOrError(state, catalog::refresh); return@Column }
            val items = catalog.active(tab)
            if (items.isEmpty()) {
                val word = stringResource(typeWord(tab)).lowercase()
                EmptyCategories(stringResource(R.string.cat_empty_title, word), stringResource(R.string.cat_empty_choose, word),
                    onManage?.let { stringResource(R.string.cat_open_settings) }, onManage)
                return@Column
            }
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)) {
                val cols = gridColumns(maxWidth.value)
                // null marks the trailing Manage tile.
                val cells: List<Category?> = items + if (onManage != null) listOf(null) else emptyList()
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    cells.chunked(cols).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { c ->
                                if (c == null) CategoryManageTile(checkNotNull(onManage), Modifier.weight(1f))
                                else CategoryGridTile(c.name, catalog.iconKey(c), CategoryVisuals.displayHex(c), c.categoryId == selectedId,
                                    { onPick(c) }, Modifier.weight(1f))
                            }
                            repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------- Category settings

@Composable
fun CategorySettingsScreen(
    catalog: CategoryCatalogViewModel,
    state: CategoryCatalogState,
    tab: TransactionType,
    onTab: (TransactionType) -> Unit,
    onBack: () -> Unit,
    onAdd: (TransactionType) -> Unit,
    onEdit: (Category) -> Unit,
) {
    val manage = catalog.canManage
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).navigationBarsPadding()) {
        CategoryPageHeader(stringResource(R.string.cs_title), leading = { HeaderBackButton(onBack) }, trailing = {
            if (state.loaded) HeaderTextButton(stringResource(if (state.reorder) R.string.cs_reorder_done else R.string.cs_reorder), catalog::toggleReorder)
        })
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp)) { CategoryTypeTabs(tab, onTab) }
        HorizontalDivider(color = LocalOwliColors.current.hairline)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            StatusLine(stringResource(if (catalog.demo) R.string.cs_demo_note else R.string.cs_account_note),
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp))
            if (!state.loaded) { LoadingOrError(state, catalog::refresh); return@Column }
            val rows = catalog.everything(tab)
            if (rows.isEmpty()) {
                EmptyCategories(stringResource(R.string.cat_empty_title, stringResource(typeWord(tab)).lowercase()),
                    stringResource(R.string.cs_empty, stringResource(typeWord(tab)).lowercase()),
                    if (manage) stringResource(R.string.cs_add) else null, if (manage) ({ onAdd(tab) }) else null)
                return@Column
            }
            Surface(Modifier.fillMaxWidth().padding(top = 14.dp), color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, LocalOwliColors.current.hairline)) {
                Column {
                    rows.forEachIndexed { index, c ->
                        if (index > 0) HorizontalDivider(color = LocalOwliColors.current.hairline)
                        CategorySettingsRow(catalog, state, c, index, rows.size, tab, onEdit)
                    }
                }
            }
            StatusLine(stringResource(if (state.reorder) R.string.cs_note_reorder else R.string.cs_note),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
            Spacer(Modifier.height(24.dp))
        }
        if (manage) Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = LocalOwliColors.current.hairline)
            Button(onClick = { onAdd(tab) }, enabled = state.loaded && state.busyId == null, shape = LocalOwliShapes.current.button,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).heightIn(min = 52.dp)) {
                Text(stringResource(R.string.cs_add), style = LocalOwliTypography.current.button)
            }
        }
    }
    CatalogDialogs(catalog, state)
    CatalogMessageBanner(catalog, state)
}

@Composable
private fun CategorySettingsRow(catalog: CategoryCatalogViewModel, state: CategoryCatalogState, c: Category, index: Int, count: Int,
                                tab: TransactionType, onEdit: (Category) -> Unit) {
    val editable = catalog.canManage && !c.isSystemDefault
    val busy = state.busyId != null
    Row(Modifier.fillMaxWidth().heightIn(min = 66.dp).padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CategoryDisc(catalog.iconKey(c), CategoryVisuals.displayHex(c), Modifier.alpha(if (c.isActive) 1f else 0.4f))
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(c.name, style = LocalOwliTypography.current.bodyStrong, modifier = Modifier.weight(1f, fill = false),
                    color = if (c.isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                if (!c.isActive) Badge(stringResource(R.string.cs_badge_inactive))
                if (c.isSystemDefault) Badge(stringResource(R.string.cs_badge_default))
            }
            Text(stringResource(when { !c.isActive -> R.string.cs_row_inactive; c.isSystemDefault -> R.string.cs_row_default; else -> R.string.cs_row_active }),
                style = LocalOwliTypography.current.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.busyId == c.categoryId) CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
        else if (state.reorder) {
            RowIcon(R.drawable.ic_cat_chevu, stringResource(R.string.cs_move_up, c.name), index > 0) { catalog.move(tab, c.categoryId, -1) }
            RowIcon(R.drawable.ic_cat_chevd, stringResource(R.string.cs_move_down, c.name), index < count - 1) { catalog.move(tab, c.categoryId, 1) }
        } else if (editable) {
            RowIcon(R.drawable.ic_cat_edit, stringResource(R.string.cs_edit, c.name), !busy) { onEdit(c) }
            if (c.isActive) RowIcon(R.drawable.ic_cat_trash, stringResource(R.string.cs_remove, c.name), !busy) { catalog.requestRemove(c) }
            else RowIcon(R.drawable.ic_cat_refresh, stringResource(R.string.cs_reactivate, c.name), !busy) { catalog.reactivate(c) }
        }
    }
}

@Composable
private fun RowIcon(icon: Int, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(painterResource(icon), contentDescription = description, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Badge(text: String) {
    Text(text, style = LocalOwliTypography.current.micro, color = LocalOwliColors.current.accent,
        modifier = Modifier.clip(LocalOwliShapes.current.chip).background(LocalOwliColors.current.accentSoft).padding(horizontal = 6.dp, vertical = 2.dp))
}

@Composable
private fun CatalogDialogs(catalog: CategoryCatalogViewModel, state: CategoryCatalogState) {
    val busy = state.busyId != null
    when (val d = state.dialog) {
        is CatalogDialog.ConfirmDelete -> AlertDialog(onDismissRequest = catalog::dismissDialog,
            title = { Text(stringResource(R.string.cs_delete_title, d.category.name)) },
            text = { Text(stringResource(R.string.cs_delete_lead)) },
            confirmButton = { TextButton(onClick = catalog::confirmDelete, enabled = !busy) {
                Text(stringResource(R.string.cs_delete_confirm), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = catalog::dismissDialog, enabled = !busy) { Text(stringResource(R.string.cat_cancel)) } })
        is CatalogDialog.InUse -> AlertDialog(onDismissRequest = catalog::dismissDialog,
            title = { Text(stringResource(R.string.cs_inuse_title, d.category.name)) },
            text = { Text(stringResource(R.string.cs_inuse_lead)) },
            confirmButton = { TextButton(onClick = catalog::deactivate, enabled = !busy) { Text(stringResource(R.string.cs_deactivate)) } },
            dismissButton = { TextButton(onClick = catalog::dismissDialog, enabled = !busy) { Text(stringResource(R.string.cs_keep)) } })
        null -> Unit
    }
}

/** Short status after a confirmed change (or an honest failure); announced politely, dismissed after a few seconds. */
@Composable
internal fun CatalogMessageBanner(catalog: CategoryCatalogViewModel, state: CategoryCatalogState) {
    val message = state.message ?: return
    LaunchedEffect(message.id) { delay(3500); catalog.consumeMessage(message.id) }
    val text = when (message.kind) {
        CatalogMessageKind.Added -> stringResource(R.string.cs_added, message.name)
        CatalogMessageKind.Updated -> stringResource(R.string.cs_updated, message.name)
        CatalogMessageKind.Deleted -> stringResource(R.string.cs_deleted, message.name)
        CatalogMessageKind.Deactivated -> stringResource(R.string.cs_deactivated, message.name)
        CatalogMessageKind.Reactivated -> stringResource(R.string.cs_reactivated, message.name)
        CatalogMessageKind.Denied -> stringResource(R.string.cs_error_denied)
        CatalogMessageKind.Conflict -> stringResource(R.string.cs_error_conflict)
        CatalogMessageKind.Failed -> stringResource(R.string.cs_error_generic)
        CatalogMessageKind.Unknown -> stringResource(R.string.cs_error_unknown)
    }
    val success = message.kind in setOf(CatalogMessageKind.Added, CatalogMessageKind.Updated, CatalogMessageKind.Deleted,
        CatalogMessageKind.Deactivated, CatalogMessageKind.Reactivated)
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 96.dp), contentAlignment = Alignment.BottomCenter) {
        Surface(shape = LocalOwliShapes.current.alert, color = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.errorContainer,
            contentColor = if (success) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onErrorContainer, shadowElevation = 6.dp) {
            Text(if (success && catalog.demo) stringResource(R.string.cs_demo_status, text) else text,
                style = LocalOwliTypography.current.label,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

// ---------------------------------------------------------------- Add / edit category

@Composable
fun CategoryEditorScreen(editor: CategoryEditorViewModel, state: CategoryEditorState, demo: Boolean) {
    val focus = LocalFocusManager.current
    val typography = LocalOwliTypography.current
    val locked = state.saving || state.saved != null
    val problem = editor.nameProblem(state)
    val showProblem = state.nameTouched && problem != null
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).navigationBarsPadding().imePadding()) {
        CategoryPageHeader(stringResource(if (state.isEdit) R.string.ce_edit_title else R.string.ce_add_title),
            leading = { HeaderTextButton(stringResource(R.string.cat_cancel), editor::back, enabled = !state.saving) })
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ce_type), style = typography.overline, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { heading() })
                CategoryTypeTabs(state.type, editor::type, enabled = !state.isEdit && !locked)
                if (state.isEdit) StatusLine(stringResource(R.string.ce_type_locked))
            }
            HorizontalDivider(color = LocalOwliColors.current.hairline)
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CategoryDisc(state.iconKey, state.displayHex, Modifier.padding(top = 4.dp), size = 56.dp, iconSize = 26.dp)
                OutlinedTextField(state.name, editor::name, enabled = !locked, singleLine = true,
                    label = { Text(stringResource(R.string.ce_name)) }, shape = LocalOwliShapes.current.field, colors = owliFieldColors(),
                    isError = showProblem || state.error == EditorError.NameConflict,
                    supportingText = {
                        when {
                            showProblem -> ErrorLine(when (problem) {
                                NameProblem.Required -> stringResource(R.string.ce_name_required)
                                NameProblem.TooLong -> stringResource(R.string.ce_name_long)
                                NameProblem.ControlCharacters -> stringResource(R.string.ce_name_single_line)
                                else -> stringResource(R.string.ce_name_duplicate, state.name.trim(), stringResource(typeWord(state.type)).lowercase())
                            })
                            state.error == EditorError.NameConflict -> ErrorLine(stringResource(R.string.ce_name_conflict))
                            else -> Text(stringResource(R.string.ce_name_hint))
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); editor.save() }),
                    modifier = Modifier.weight(1f))
            }
            EditorPreview(state)
            SectionLabel(stringResource(R.string.ce_color))
            val swatches = listOfNotNull(state.customHex?.let { it to null }) + CategoryVisuals.palette.map { it.hex to it.name }
            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                val perRow = if (LocalConfiguration.current.fontScale > 1.5f) 4 else 5
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    swatches.chunked(perRow).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            row.forEach { (hex, name) ->
                                val label = name?.let { stringResource(it) } ?: stringResource(R.string.ce_color_current, hex)
                                Swatch(hex, label, state.colorHex == hex, !locked) { editor.color(hex) }
                            }
                            repeat(perRow - row.size) { Spacer(Modifier.size(48.dp)) }
                        }
                    }
                }
            }
            SectionLabel(stringResource(R.string.ce_icon))
            StatusLine(stringResource(if (demo) R.string.ce_icon_demo_note else R.string.ce_icon_device_note),
                modifier = Modifier.padding(horizontal = 20.dp))
            CategoryVisuals.groups.forEach { group ->
                Text(stringResource(group.title), style = typography.caption, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp).semantics { heading() })
                val perRow = if (LocalConfiguration.current.fontScale > 1.5f) 5 else 6
                Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    group.icons.chunked(perRow).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            row.forEach { icon -> IconChoice(icon, state.iconKey == icon.key, state.displayHex, !locked) { editor.icon(icon.key) } }
                            repeat(perRow - row.size) { Spacer(Modifier.size(48.dp)) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = LocalOwliColors.current.hairline)
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state.error) {
                    EditorError.Failed -> ErrorLine(stringResource(R.string.ce_save_failed))
                    EditorError.Unknown -> ErrorLine(stringResource(R.string.ce_save_unknown))
                    EditorError.Denied -> ErrorLine(stringResource(R.string.cs_error_denied))
                    EditorError.TypeConflict -> ErrorLine(stringResource(R.string.ce_type_conflict))
                    else -> Unit
                }
                Button(onClick = { focus.clearFocus(); editor.save() }, enabled = !locked && state.error != EditorError.Unknown,
                    shape = LocalOwliShapes.current.button, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    if (state.saving) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(if (state.saving) R.string.ce_saving else if (state.isEdit) R.string.ce_save_edit else R.string.ce_save_add),
                        style = LocalOwliTypography.current.button)
                }
            }
        }
    }
    if (state.discardConfirmation) AlertDialog(onDismissRequest = editor::keepEditing,
        title = { Text(stringResource(R.string.ce_discard_title)) }, text = { Text(stringResource(R.string.ce_discard_lead)) },
        confirmButton = { TextButton(onClick = editor::discard) { Text(stringResource(R.string.ce_discard), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = editor::keepEditing) { Text(stringResource(R.string.ce_keep)) } })
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = LocalOwliTypography.current.overline, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 10.dp).semantics { heading() })
}

@Composable
private fun ErrorLine(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = LocalOwliTypography.current.caption,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun EditorPreview(state: CategoryEditorState) {
    val name = state.name.trim()
    Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), shape = LocalOwliShapes.current.group,
        color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, LocalOwliColors.current.hairline)) {
        Column(Modifier.padding(14.dp).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.ce_preview), style = LocalOwliTypography.current.overline, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CategoryGridTile(name.ifEmpty { stringResource(R.string.ce_preview_name) }, state.iconKey, state.displayHex, false, null, Modifier.width(88.dp))
                Row(Modifier.weight(1f).clip(LocalOwliShapes.current.field).background(MaterialTheme.colorScheme.background)
                    .border(BorderStroke(1.dp, LocalOwliColors.current.hairline), LocalOwliShapes.current.field).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CategoryDisc(state.iconKey, state.displayHex, size = 36.dp, iconSize = 18.dp)
                    Text(name.ifEmpty { stringResource(R.string.ce_preview_name) }, style = LocalOwliTypography.current.bodyStrong,
                        color = if (name.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun Swatch(hex: String, label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val color = categoryColor(hex)
    val surface = MaterialTheme.colorScheme.surface
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .semantics { contentDescription = label; this.selected = selected }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(44.dp).then(if (selected) Modifier.border(2.dp, color, CircleShape).padding(4.dp).border(0.dp, surface, CircleShape) else Modifier)
            .clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            if (selected) Icon(painterResource(R.drawable.ic_cat_check), null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun IconChoice(icon: CategoryIcon, selected: Boolean, hex: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalOwliColors.current
    val label = stringResource(icon.label)
    val selectedText = stringResource(R.string.cat_selected)
    val tint = categoryColor(hex)
    Box(Modifier.size(48.dp).clip(CircleShape).background(if (selected) tint else colors.tile)
        .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .semantics { contentDescription = label; this.selected = selected; if (selected) stateDescription = selectedText },
        contentAlignment = Alignment.Center) {
        Icon(painterResource(icon.drawable), null, tint = if (selected) Color.White else colors.tileContent, modifier = Modifier.size(22.dp))
    }
}

// ---------------------------------------------------------------- Confirmation

data class TransactionReceipt(val type: TransactionType, val categoryName: String, val amountText: String, val dateText: String,
                              val demo: Boolean, val replayed: Boolean = false, val queuedOffline: Boolean = false,
                              /** Server-confirmed row in another currency: its authoritative rate, rate date and base amount are shown. */
                              val converted: com.elect3.mobdev.personalfinance.domain.model.Transaction? = null,
                              val baseCurrency: String? = null,
                              /** Queued offline in another currency: no rate exists yet; the server converts it when it syncs. */
                              val pendingConversion: Boolean = false)

@Composable
fun TransactionConfirmationScreen(receipt: TransactionReceipt, onDashboard: () -> Unit, onAnother: () -> Unit) {
    val colors = LocalOwliColors.current
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()
        .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.surface, colors.income, 0.22f)),
            contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_cat_check), null, tint = colors.income, modifier = Modifier.size(32.dp))
        }
        Text(stringResource(if (receipt.demo) R.string.tx_demo_saved_title else if (receipt.queuedOffline) R.string.tx_queued_title else R.string.tx_saved_title),
            style = LocalOwliTypography.current.screenTitle, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
        Text(stringResource(if (receipt.demo) R.string.tx_demo_saved_lead else if (receipt.queuedOffline) R.string.tx_queued_lead
            else if (receipt.replayed) R.string.tx_saved_replayed_lead else R.string.tx_saved_lead),
            style = LocalOwliTypography.current.body, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Surface(Modifier.fillMaxWidth().padding(top = 8.dp), shape = LocalOwliShapes.current.group, color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, colors.hairline)) {
            Column {
                ReceiptRow(stringResource(R.string.tx_sum_type), stringResource(typeWord(receipt.type)))
                HorizontalDivider(color = colors.hairline)
                ReceiptRow(stringResource(R.string.tx_sum_category), receipt.categoryName)
                HorizontalDivider(color = colors.hairline)
                ReceiptRow(stringResource(R.string.tx_sum_amount), receipt.amountText, amount = true)
                HorizontalDivider(color = colors.hairline)
                ReceiptRow(stringResource(R.string.tx_sum_date), receipt.dateText)
            }
        }
        val converted = receipt.converted
        if (converted != null && receipt.baseCurrency != null && converted.currencyCode != receipt.baseCurrency)
            com.elect3.mobdev.personalfinance.ui.transactions.currency.ConversionDetailsCard(converted, receipt.baseCurrency)
        if (receipt.pendingConversion && receipt.baseCurrency != null)
            Text(stringResource(R.string.fx_receipt_queued, receipt.baseCurrency), style = LocalOwliTypography.current.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Button(onClick = onDashboard, shape = LocalOwliShapes.current.button, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(stringResource(R.string.tx_back_dashboard), style = LocalOwliTypography.current.button)
        }
        OutlinedButton(onClick = onAnother, shape = LocalOwliShapes.current.button, border = BorderStroke(1.dp, colors.hairline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(stringResource(R.string.tx_add_another), style = LocalOwliTypography.current.button)
        }
    }
}

@Composable
private fun ColumnScope.ReceiptRow(label: String, value: String, amount: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = LocalOwliTypography.current.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = if (amount) LocalOwliTypography.current.amountRow else LocalOwliTypography.current.bodyStrong,
            textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}
