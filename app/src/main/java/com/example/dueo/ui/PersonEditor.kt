package com.example.dueo.ui

import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.dueo.ui.theme.DueoDesign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PersonEditor(saving: Boolean, error: String? = null, onDismiss: () -> Unit, onSave: (String, String, String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var cropUri by rememberSaveable { mutableStateOf<String?>(null) }
    var photo by rememberSaveable { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) cropUri = uri.toString()
    }
    val contacts = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK && uri != null) {
            importing = true
            scope.launch {
                try {
                    val fields = withContext(Dispatchers.IO) {
                        context.contentResolver.query(uri, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.PHOTO_URI), null, null, null)?.use { cursor ->
                            check(cursor.moveToFirst())
                            Triple(cursor.getString(0).orEmpty(), cursor.getString(1).orEmpty(), cursor.getString(2))
                        } ?: error("Contact unavailable")
                    }
                    name = fields.first.take(60); phone = fields.second.take(40)
                    cropUri = fields.third?.takeIf { it.isNotBlank() }
                    importError = null
                } catch (_: Exception) { importError = "Couldn't read that contact. You can enter their details manually." }
                finally { importing = false }
            }
        }
    }
    Dialog(onDismissRequest = { if (!saving && !importing) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BackHandler(cropUri != null) { cropUri = null }
            AnimatedContent(cropUri, transitionSpec = { pageMotion(targetState != null) }, label = "profile crop") { uri ->
                if (uri != null) SquarePhotoCropper(Uri.parse(uri), onCancel = { cropUri = null }, onCropped = { photo = it; cropUri = null })
                else Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss, enabled = !saving && !importing) { LineIcon(Glyph.BACK, "Cancel adding person") }
                        Text("Add person", style = MaterialTheme.typography.headlineMedium)
                    }
                    Text("Create their profile first. Record money with them whenever you need.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Avatar(name.ifBlank { "?" }, Modifier.size(96.dp), photo)
                        TextButton(onClick = {
                            runCatching { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                                .onFailure { importError = "No photo picker is available on this device." }
                        }, enabled = !saving && !importing) { Text(if (photo == null) "Choose photo" else "Change photo") }
                        if (photo != null) TextButton(onClick = { photo = null }, enabled = !saving) { Text("Remove photo") }
                        Text("Optional · cropped to a square", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = {
                        runCatching { contacts.launch(Intent(Intent.ACTION_PICK, Phone.CONTENT_URI)) }
                            .onFailure { importError = "No contacts picker is available. Enter the details below." }
                    }, enabled = !saving && !importing, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(if (importing) "Importing…" else "Choose from contacts")
                    }
                    OutlinedTextField(name, { name = it.take(60) }, label = { Text("Name *") }, supportingText = { Text("Required") },
                        singleLine = true, enabled = !saving && !importing, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), modifier = Modifier.fillMaxWidth(), shape = DueoDesign.SmallShape)
                    OutlinedTextField(phone, { phone = it.take(40) }, label = { Text("Phone number (optional)") }, singleLine = true,
                        enabled = !saving && !importing, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(), shape = DueoDesign.SmallShape)
                    if (importError != null || error != null) Text(error ?: importError.orEmpty(), color = MaterialTheme.colorScheme.error)
                    Button(onClick = { if (name.isNotBlank() && !saving) onSave(name.trim(), phone.trim(), photo) },
                        enabled = name.isNotBlank() && !saving && !importing, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Text(if (saving) "Saving person…" else "Save person")
                    }
                }
            }
        }
    }
}
