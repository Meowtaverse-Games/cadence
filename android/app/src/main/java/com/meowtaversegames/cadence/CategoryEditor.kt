package com.meowtaversegames.cadence

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun CategoryNameDialog(initial: String = "", onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    var attempted by remember { mutableStateOf(false) }
    val error = categoryNameError(name)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initial.isEmpty()) "カテゴリを作る" else "名前の変更") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
            label = { Text("カテゴリ名") }, isError = attempted && error != null,
            supportingText = { if (attempted && error != null) Text(error) }) },
        confirmButton = { TextButton(onClick = { attempted = true; if (error == null) onSave(name.trim()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } })
}

@Composable
fun CategoryMembershipDialog(app: AppEntry, categories: List<HomeCategory>, installedIds: Set<String>?,
    failed: Boolean, onCategories: (List<HomeCategory>) -> Unit, onDismiss: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("カテゴリに入れる…") }, text = {
        Column {
            if (failed) Text("カテゴリを読み込めません")
            if (categories.isEmpty()) TextButton(enabled = !failed && installedIds != null,
                onClick = { creating = true }) { Text("カテゴリを作る") }
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(categories, key = { it.id }) { category ->
                    val checked = app.id in category.appIds
                    CategoryCheckRow(category.name, checked, !failed && (checked || installedIds != null)) {
                        onCategories(updateCategory(categories, category.id) {
                            it.copy(appIds = if (checked) removeFavorite(it.appIds, app.id)
                                else addFavorite(it.appIds, app.id, installedIds))
                        })
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } })
    if (creating) CategoryNameDialog(onDismiss = { creating = false }) { name ->
        val category = newCategory(name)
        onCategories(categories + category.copy(appIds = addFavorite(emptyList(), app.id, installedIds)))
        creating = false
    }
}

@Composable
private fun CategoryCheckRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, onClick = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = { onChange() })
        Text(label, Modifier.weight(1f))
    }
}

@Composable
fun CategorySettings(categories: List<HomeCategory>, failed: Boolean, apps: List<AppEntry>, installedIds: Set<String>?,
    onCategories: (List<HomeCategory>) -> Unit) {
    var naming by remember { mutableStateOf<HomeCategory?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<HomeCategory?>(null) }
    var selecting by remember { mutableStateOf<String?>(null) }
    Column {
        Text("カテゴリ", style = MaterialTheme.typography.titleLarge)
        if (failed) Text("カテゴリを読み込めません。元のファイルを保護するため保存できません")
        categories.forEachIndexed { index, category ->
            TextButton(enabled = !failed, onClick = { selecting = category.id }) { Text(category.name) }
            Row {
                TextButton(enabled = !failed, onClick = { naming = category }) { Text("名前の変更") }
                TextButton(enabled = !failed && index > 0,
                    onClick = { onCategories(moveCategory(categories, category.id, -1)) }) { Text("上へ") }
                TextButton(enabled = !failed && index < categories.lastIndex,
                    onClick = { onCategories(moveCategory(categories, category.id, 1)) }) { Text("下へ") }
                TextButton(enabled = !failed, onClick = { deleting = category }) { Text("削除") }
            }
        }
        TextButton(enabled = !failed, onClick = { creating = true }) { Text("カテゴリを作る") }
    }
    if (creating || naming != null) CategoryNameDialog(initial = naming?.name.orEmpty(),
        onDismiss = { creating = false; naming = null }) { name ->
        onCategories(naming?.let { category -> updateCategory(categories, category.id) { renameCategory(it, name) } }
            ?: (categories + newCategory(name)))
        creating = false; naming = null
    }
    deleting?.let { category ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("「${category.name}」を削除しますか？") },
            text = { Text("中のアプリはアンインストールされません") },
            confirmButton = { TextButton(onClick = {
                onCategories(removeCategory(categories, category.id)); deleting = null
            }) { Text("削除") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("キャンセル") } })
    }
    categories.firstOrNull { it.id == selecting }?.let { category ->
        CategoryAppSelection(category, apps, installedIds, failed, { selecting = null }) { selected ->
            onCategories(updateCategory(categories, category.id) {
                it.copy(appIds = applyCategorySelection(it.appIds, selected, apps, installedIds))
            })
            selecting = null
        }
    }
}

@Composable
private fun CategoryAppSelection(category: HomeCategory, apps: List<AppEntry>, installedIds: Set<String>?,
    failed: Boolean, onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
    var selected by remember(category.id) { mutableStateOf(category.appIds.toSet()) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(20.dp)) {
                Text(category.name, style = MaterialTheme.typography.headlineMedium)
                Text("入れるアプリを選んで保存してください")
                if (installedIds == null) Text("アプリの一覧を読み込めません")
                LazyColumn(Modifier.weight(1f)) {
                    items(apps, key = { it.id }) { app ->
                        CategoryCheckRow(app.label, app.id in selected, !failed && installedIds != null) {
                            selected = if (app.id in selected) selected - app.id else selected + app.id
                        }
                    }
                }
                Row {
                    TextButton(onClick = onDismiss) { Text("キャンセル") }
                    Button(enabled = !failed && installedIds != null, onClick = { onSave(selected) }) { Text("保存") }
                }
            }
        }
        BackHandler(onBack = onDismiss)
    }
}
