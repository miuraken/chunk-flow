package com.miuraken.chunkflow.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.model.LibraryTags

/*
 * ライブラリのファイルのタグの UI 部品（ADR 0013）。
 *
 * - ファイルの行の小さなタグの表示（[LibraryItemTagPills]）
 * - ファイルの設定のシートのタグ欄（[ItemTagEditor]）
 * - 選択モードの「タグを編集」ダイアログ（[EditTagsForSelectionDialog]）
 * - ファイルタブのタグの絞り込み（[FileTagFilterRow]）
 */

/** ファイルの行に並べるタグの最大数。超えた分は「+N」にまとめる。 */
private const val MAX_VISIBLE_TAG_PILLS = 3

/** タグの入力欄に入れられる最大の文字数（貼り付けで極端に長い文字列が入らないようにする）。名前の上限は [LibraryTags.MAX_NAME_LENGTH]。 */
private const val MAX_TAG_INPUT_CHARS = 100

/** タグの入力欄の文字列を、付けるタグに加えた結果。 */
internal sealed interface TagInputResult {
    /** 加えた結果の一覧（同じタグがすでにあれば一覧は変わらない）。 */
    data class Added(val tags: List<String>) : TagInputResult

    /** 名前が正しくない（空・長すぎる・制御文字を含む）。 */
    data object Invalid : TagInputResult

    /** 1 つのファイルに付けられるタグの上限（[LibraryTags.MAX_TAGS_PER_ITEM]）に達している。 */
    data object LimitReached : TagInputResult
}

/**
 * 入力されたタグ名 [rawInput] を、付けるタグ [current] の末尾に加える。
 *
 * 名前は [LibraryTags.normalizeName] でそろえ、大文字・小文字や全角・半角の違いだけの既存のタグ（[availableTags]）が
 * あればその表記にそろえる。すでに付いているタグなら一覧を変えない。
 */
internal fun addTagFromInput(
    current: List<String>,
    rawInput: String,
    availableTags: List<String>,
    maxTags: Int = LibraryTags.MAX_TAGS_PER_ITEM,
): TagInputResult {
    val name = LibraryTags.normalizeName(rawInput) ?: return TagInputResult.Invalid
    val key = LibraryTags.key(name)
    if (current.any { LibraryTags.key(it) == key }) return TagInputResult.Added(current)
    if (current.size >= maxTags) return TagInputResult.LimitReached
    val canonical = availableTags.firstOrNull { LibraryTags.key(it) == key } ?: name
    return TagInputResult.Added(current + canonical)
}

/**
 * 保存するときに付けるタグの一覧。入力途中のタグ [pendingInput] があれば加える。
 *
 * 入力途中のタグが正しくなければ、その理由（[TagInputResult.Invalid] / [TagInputResult.LimitReached]）を返す。
 */
internal fun resolveTagsForSave(
    tags: List<String>,
    pendingInput: String,
    availableTags: List<String>,
): TagInputResult = if (pendingInput.isBlank()) {
    TagInputResult.Added(tags)
} else {
    addTagFromInput(tags, pendingInput, availableTags)
}

/** 「タグを編集」のダイアログの、タグごとのチェックの状態。 */
internal enum class TagCheckState {
    /** 選んだファイルのすべてに付いている（付ける）。 */
    ON,

    /** 選んだファイルのどれにも付いていない（外す）。 */
    OFF,

    /** 一部のファイルにだけ付いている（変えない）。 */
    MIXED,
}

/** 選んだファイル [targetCount] 件のうち [count] 件にタグが付いているときの、チェックの初期状態。 */
internal fun initialTagCheckState(count: Int, targetCount: Int): TagCheckState = when {
    targetCount > 0 && count >= targetCount -> TagCheckState.ON
    count > 0 -> TagCheckState.MIXED
    else -> TagCheckState.OFF
}

/**
 * チェックを押したときの次の状態。オン → オフ →（一部で始まったものだけ）一部 → オン の順に変わる。
 */
internal fun nextTagCheckState(current: TagCheckState, initial: TagCheckState): TagCheckState = when (current) {
    TagCheckState.ON -> TagCheckState.OFF
    TagCheckState.OFF -> if (initial == TagCheckState.MIXED) TagCheckState.MIXED else TagCheckState.ON
    TagCheckState.MIXED -> TagCheckState.ON
}

/**
 * 「タグを編集」で付けるタグと外すタグ。
 *
 * @property addTags 選んだファイルのすべてに付けるタグ。
 * @property removeTags 選んだファイルのすべてから外すタグ。
 */
internal data class TagEditChanges(
    val addTags: Set<String>,
    val removeTags: Set<String>,
) {
    /** 変更がないかどうか。 */
    val isEmpty: Boolean
        get() = addTags.isEmpty() && removeTags.isEmpty()
}

/**
 * チェックの初期状態 [initial] と現在の状態 [current] から、付けるタグと外すタグを求める。
 *
 * [initial] にないタグ（ダイアログで新しく作ったタグ）は、どのファイルにも付いていなかったものとして扱う。
 */
internal fun tagEditChanges(
    initial: Map<String, TagCheckState>,
    current: Map<String, TagCheckState>,
): TagEditChanges {
    val add = LinkedHashSet<String>()
    val remove = LinkedHashSet<String>()
    for ((tag, state) in current) {
        val before = initial[tag] ?: TagCheckState.OFF
        if (state == TagCheckState.ON && before != TagCheckState.ON) add += tag
        if (state == TagCheckState.OFF && before != TagCheckState.OFF) remove += tag
    }
    return TagEditChanges(addTags = add, removeTags = remove)
}

private fun TagCheckState.toToggleableState(): ToggleableState = when (this) {
    TagCheckState.ON -> ToggleableState.On
    TagCheckState.OFF -> ToggleableState.Off
    TagCheckState.MIXED -> ToggleableState.Indeterminate
}

/**
 * ファイルの行に出す小さなタグの表示。[maxVisible] 個まで並べ、残りは「+N」にまとめる。
 *
 * 読み上げでは「タグ: A, B, C」とまとめて読む。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LibraryItemTagPills(
    tags: List<String>,
    modifier: Modifier = Modifier,
    maxVisible: Int = MAX_VISIBLE_TAG_PILLS,
) {
    if (tags.isEmpty()) return
    val visibleTags = tags.take(maxVisible)
    val hiddenCount = tags.size - visibleTags.size
    val description = stringResource(R.string.library_item_tags_cd, tags.joinToString(separator = ", "))
    FlowRow(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        visibleTags.forEach { tag ->
            TagPill(text = tag, showIcon = true)
        }
        if (hiddenCount > 0) {
            TagPill(text = stringResource(R.string.library_item_tags_more, hiddenCount), showIcon = false)
        }
    }
}

@Composable
private fun TagPill(
    text: String,
    showIcon: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (showIcon) {
                Icon(
                    imageVector = ChunkFlowIcons.Tag,
                    contentDescription = null,
                    modifier = Modifier.size(11.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp),
            )
        }
    }
}

/**
 * ファイルの設定のシートのタグ欄。
 *
 * 付いているタグ（押すと外す）、新しいタグの入力欄、既存のタグから付けるチップを並べる。
 * 入力欄の文字列は呼び出し側が持ち、保存するときに入力途中のタグも加えられるようにする（[resolveTagsForSave]）。
 *
 * @param tags 付けるタグ（編集中の値）。
 * @param availableTags ライブラリにある既存のタグ（作られた順）。
 * @param input 新しいタグの入力欄の文字列。
 * @param inputError 入力欄の誤り（[TagInputResult.Invalid] / [TagInputResult.LimitReached]）。なければ `null`。
 * @param onInputChange 入力欄の文字列が変わったとき。
 * @param onSubmitInput 入力欄のタグを加えるとき（追加ボタン・キーボードの完了）。
 * @param onTagsChange 付けるタグが変わったとき（外す・既存のタグを付ける）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ItemTagEditor(
    tags: List<String>,
    availableTags: List<String>,
    input: String,
    inputError: TagInputResult?,
    onInputChange: (String) -> Unit,
    onSubmitInput: () -> Unit,
    onTagsChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val attachedKeys = remember(tags) { tags.mapTo(HashSet(), LibraryTags::key) }
    val suggestions = remember(availableTags, attachedKeys) {
        availableTags.filter { LibraryTags.key(it) !in attachedKeys }
    }
    val isLimitReached = tags.size >= LibraryTags.MAX_TAGS_PER_ITEM
    val supportingMessage = when {
        inputError == TagInputResult.Invalid ->
            stringResource(R.string.library_tag_invalid, LibraryTags.MAX_NAME_LENGTH)

        inputError == TagInputResult.LimitReached || isLimitReached ->
            stringResource(R.string.library_tag_limit_reached, LibraryTags.MAX_TAGS_PER_ITEM)

        else -> null
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.library_tags_section_title),
            style = MaterialTheme.typography.titleSmall,
        )
        if (tags.isEmpty()) {
            Text(
                text = stringResource(R.string.library_tags_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.forEach { tag ->
                    val removeCd = stringResource(R.string.library_tag_remove_cd, tag)
                    AssistChip(
                        onClick = { onTagsChange(tags - tag) },
                        label = {
                            Text(
                                text = tag,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 200.dp),
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = ChunkFlowIcons.Tag,
                                contentDescription = null,
                                modifier = Modifier.size(AssistChipDefaults.IconSize),
                            )
                        },
                        trailingIcon = {
                            Icon(
                                imageVector = ChunkFlowIcons.Close,
                                contentDescription = null,
                                modifier = Modifier.size(AssistChipDefaults.IconSize),
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            trailingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                        border = null,
                        modifier = Modifier.semantics { contentDescription = removeCd },
                    )
                }
            }
        }
        OutlinedTextField(
            value = input,
            onValueChange = { onInputChange(it.take(MAX_TAG_INPUT_CHARS)) },
            label = { Text(text = stringResource(R.string.library_tag_new_label)) },
            singleLine = true,
            enabled = !isLimitReached,
            isError = inputError != null,
            supportingText = if (supportingMessage != null) {
                { Text(text = supportingMessage) }
            } else {
                null
            },
            trailingIcon = {
                IconButton(
                    onClick = onSubmitInput,
                    enabled = !isLimitReached && input.isNotBlank(),
                ) {
                    Icon(
                        imageVector = ChunkFlowIcons.Add,
                        contentDescription = stringResource(R.string.library_tag_add_cd),
                    )
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmitInput() }),
            modifier = Modifier.fillMaxWidth(),
        )
        if (suggestions.isNotEmpty() && !isLimitReached) {
            Text(
                text = stringResource(R.string.library_tags_add_existing_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { tag ->
                    val addCd = stringResource(R.string.library_tag_add_existing_cd, tag)
                    SuggestionChip(
                        onClick = { onTagsChange(tags + tag) },
                        label = {
                            Text(
                                text = tag,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 200.dp),
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = ChunkFlowIcons.Add,
                                contentDescription = null,
                                modifier = Modifier.size(AssistChipDefaults.IconSize),
                            )
                        },
                        modifier = Modifier.semantics { contentDescription = addCd },
                    )
                }
            }
        }
    }
}

/**
 * 複数選択したファイルのタグをまとめて付け外しするダイアログ（選択モードの「タグを編集」）。
 *
 * 各タグのチェックは、選んだファイルのすべてに付いていればオン、一部なら中間（−）、どれにもなければオフで始まる。
 * 押すたびにオン → オフ →（中間で始まったものだけ）中間 の順に変わり、中間のタグは変更しない。
 * 新しいタグは下の欄で作り、作ったタグはオン（選んだファイルのすべてに付ける）で並べる。
 *
 * @param allTags ライブラリにある既存のタグ（作られた順）。
 * @param tagCounts 選んだファイルのうち、各タグが付いているファイルの数。
 * @param targetCount 選んだファイルの数。
 * @param onConfirm 「適用」で、付けるタグと外すタグを渡す。
 * @param onDismiss 閉じるとき。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditTagsForSelectionDialog(
    allTags: List<String>,
    tagCounts: Map<String, Int>,
    targetCount: Int,
    onConfirm: (TagEditChanges) -> Unit,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        EditTagsForSelectionCard(
            allTags = allTags,
            tagCounts = tagCounts,
            targetCount = targetCount,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )
    }
}

/**
 * [EditTagsForSelectionDialog] の中身（プレビューとスクリーンショットテストでも使う）。
 *
 * チェックの初期状態は、表示したときの [allTags] と [tagCounts] で決める（開いている間に変わっても選び直しにしない）。
 */
@Composable
internal fun EditTagsForSelectionCard(
    allTags: List<String>,
    tagCounts: Map<String, Int>,
    targetCount: Int,
    onConfirm: (TagEditChanges) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val initialStates = remember {
        allTags.associateWith { tag -> initialTagCheckState(tagCounts[tag] ?: 0, targetCount) }
    }
    val states = remember { mutableStateMapOf<String, TagCheckState>().apply { putAll(initialStates) } }
    val tagOrder = remember { mutableStateListOf<String>().apply { addAll(allTags) } }
    var input by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<TagInputResult?>(null) }

    // 入力欄のタグを一覧に加える（既存のタグならオンにする）。正しくなければ false
    fun submitInput(): Boolean {
        if (input.isBlank()) return true
        val name = LibraryTags.normalizeName(input)
        if (name == null) {
            inputError = TagInputResult.Invalid
            return false
        }
        val key = LibraryTags.key(name)
        val existing = tagOrder.firstOrNull { LibraryTags.key(it) == key }
        if (existing != null) {
            states[existing] = TagCheckState.ON
        } else {
            tagOrder += name
            states[name] = TagCheckState.ON
        }
        input = ""
        inputError = null
        return true
    }

    val changes = tagEditChanges(initialStates, states)
    val hasMixed = initialStates.values.any { it == TagCheckState.MIXED }

    Surface(
        modifier = modifier,
        shape = AlertDialogDefaults.shape,
        color = AlertDialogDefaults.containerColor,
        tonalElevation = AlertDialogDefaults.TonalElevation,
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = stringResource(R.string.library_tags_edit_dialog_title),
                style = MaterialTheme.typography.headlineSmall,
                color = AlertDialogDefaults.titleContentColor,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.library_tags_edit_dialog_target_count, targetCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (tagOrder.isEmpty()) {
                Text(
                    text = stringResource(R.string.library_tags_edit_dialog_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AlertDialogDefaults.textContentColor,
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(items = tagOrder, key = { it }) { tag ->
                        val state = states[tag] ?: TagCheckState.OFF
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .triStateToggleable(
                                    state = state.toToggleableState(),
                                    role = Role.Checkbox,
                                    onClick = {
                                        states[tag] = nextTagCheckState(
                                            current = state,
                                            initial = initialStates[tag] ?: TagCheckState.OFF,
                                        )
                                    },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TriStateCheckbox(state = state.toToggleableState(), onClick = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = tag,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                if (hasMixed) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.library_tags_edit_dialog_mixed_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it.take(MAX_TAG_INPUT_CHARS)
                    inputError = null
                },
                label = { Text(text = stringResource(R.string.library_tag_new_label)) },
                singleLine = true,
                isError = inputError != null,
                supportingText = if (inputError != null) {
                    { Text(text = stringResource(R.string.library_tag_invalid, LibraryTags.MAX_NAME_LENGTH)) }
                } else {
                    null
                },
                trailingIcon = {
                    IconButton(
                        onClick = { submitInput() },
                        enabled = input.isNotBlank(),
                    ) {
                        Icon(
                            imageVector = ChunkFlowIcons.Add,
                            contentDescription = stringResource(R.string.library_tag_add_cd),
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submitInput() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.library_edit_cancel))
                }
                TextButton(
                    onClick = {
                        if (submitInput()) {
                            onConfirm(tagEditChanges(initialStates, states))
                        }
                    },
                    enabled = !changes.isEmpty || input.isNotBlank(),
                ) {
                    Text(text = stringResource(R.string.library_tags_edit_apply))
                }
            }
        }
    }
}

/**
 * ファイルタブのタグの絞り込み。選んだタグをすべて持つファイルに絞る（AND）。
 *
 * 先頭は幅を固定し、タグを選んでいないときはタグのアイコン、選んでいるときは選択を解除するボタンにする
 * （チップの位置がずれて押し間違えないように）。タグが 1 つ以上あるときだけ呼び出す。
 *
 * @param tags ライブラリにあるタグ（作られた順）。
 * @param selectedTags 選んでいるタグ。
 * @param onToggleTag タグを押したとき。
 * @param onClear 選択を解除するとき。
 */
@Composable
internal fun FileTagFilterRow(
    tags: List<String>,
    selectedTags: Set<String>,
    onToggleTag: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .padding(start = 4.dp)
                .size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (selectedTags.isEmpty()) {
                Icon(
                    imageVector = ChunkFlowIcons.Tag,
                    contentDescription = stringResource(R.string.library_files_tag_filter_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = ChunkFlowIcons.Close,
                        contentDescription = stringResource(R.string.library_files_tag_filter_clear),
                    )
                }
            }
        }
        LazyRow(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(items = tags, key = { it }) { tag ->
                val selected = tag in selectedTags
                FilterChip(
                    selected = selected,
                    onClick = { onToggleTag(tag) },
                    label = {
                        Text(
                            text = tag,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 200.dp),
                        )
                    },
                    leadingIcon = if (selected) {
                        {
                            Icon(
                                imageVector = ChunkFlowIcons.Check,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@ChunkFlowPreviews
@Composable
private fun EditTagsForSelectionCardPreview() {
    ChunkFlowTheme {
        EditTagsForSelectionCard(
            allTags = listOf("N5", "シャドーイング", "旅行", "復習"),
            tagCounts = mapOf("N5" to 3, "シャドーイング" to 1),
            targetCount = 3,
            onConfirm = {},
            onDismiss = {},
        )
    }
}
