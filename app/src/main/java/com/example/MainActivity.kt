package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.ContactsContract
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : FragmentActivity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var storeListener: ListenerRegistration? = null

    // File chooser (used by Restore backup and Import CSV/Excel)
    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val result = if (r.resultCode == Activity.RESULT_OK)
            WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data) else null
        fileCallback?.onReceiveValue(result)
        fileCallback = null
    }

    // Contacts permission for full/bulk contacts sync
    private val contactsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            sendContacts()
        } else {
            toast("Allow Contacts permission to pick contacts")
            runOnUiThread {
                web.evaluateJavascript("window.onContactsPermissionDenied && window.onContactsPermissionDenied()", null)
            }
        }
    }

    // Single contact picker (native Android Contacts app picker)
    private val singleContactPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            val contactUri: Uri? = r.data?.data
            if (contactUri != null) {
                var name = ""
                var number = ""
                try {
                    val projection = arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    )
                    contentResolver.query(contactUri, projection, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                            if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: ""
                            if (numberIndex >= 0) number = cursor.getString(numberIndex) ?: ""
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                if (name.isEmpty() && number.isEmpty()) {
                    try {
                        contentResolver.query(contactUri, null, null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                                if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: ""
                                val idIdx = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                                if (idIdx >= 0) {
                                    val id = cursor.getString(idIdx)
                                    contentResolver.query(
                                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                                        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                                        arrayOf(id),
                                        null
                                    )?.use { pCursor ->
                                        if (pCursor.moveToFirst()) {
                                            number = pCursor.getString(0) ?: ""
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                val safeName = JSONObject.quote(name)
                val safeNumber = JSONObject.quote(number)
                runOnUiThread {
                    web.evaluateJavascript("window.onSingleContactPicked && window.onSingleContactPicked($safeName, $safeNumber);", null)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.setBackgroundColor("#161214".toColorInt())
        setContentView(web)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }
        web.addJavascriptInterface(Bridge(), "AndroidApp")

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == "appassets.androidplatform.net") return false
                openExternal(request.url)   // WhatsApp, SMS, links
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                notifyAuthState()
                if (Firebase.auth.currentUser != null) {
                    startStoreSyncListener()
                } else {
                    attemptAutoSignIn()
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            // Links with target="_blank" (WhatsApp / SMS buttons)
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                val temp = WebView(this@MainActivity)
                temp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        openExternal(request.url)
                        return true
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = temp
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowFileChooser(webView: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    chooser.launch(params.createIntent())
                    true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }
        }

        onBackPressedDispatcher.addCallback(this) {
            if (web.canGoBack()) {
                web.goBack()
            } else {
                finish()
            }
        }

        Firebase.auth.addAuthStateListener { auth ->
            notifyAuthState()
            if (auth.currentUser != null) {
                startStoreSyncListener()
            } else {
                storeListener?.remove()
                storeListener = null
            }
        }

        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    override fun onDestroy() {
        super.onDestroy()
        storeListener?.remove()
        storeListener = null
    }

    private fun startStoreSyncListener() {
        storeListener?.remove()
        val user = Firebase.auth.currentUser ?: return
        val db = FirebaseFirestore.getInstance(getString(R.string.firestore_database_id))
        storeListener = db.collection("store").addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e("Sync", "Snapshot listener error: ${error.message}", error)
                val errMsg = JSONObject.quote(error.message ?: "Sync error")
                runOnUiThread {
                    web.evaluateJavascript("window.onFirestoreSyncError && window.onFirestoreSyncError($errMsg);", null)
                }
                return@addSnapshotListener
            }
            if (snapshot != null) {
                val docsObj = JSONObject()
                for (doc in snapshot.documents) {
                    val data = doc.data
                    if (data != null) {
                        docsObj.put(doc.id, mapToJsonObject(data))
                    }
                }
                val jsonStr = docsObj.toString()
                val quoted = JSONObject.quote(jsonStr)
                runOnUiThread {
                    web.evaluateJavascript("window.onFirestoreStoreSnapshot && window.onFirestoreStoreSnapshot(JSON.parse($quoted));", null)
                }
            }
        }
    }

    private fun notifyAuthState() {
        val user = Firebase.auth.currentUser
        val email = JSONObject.quote(user?.email ?: "")
        val uid = JSONObject.quote(user?.uid ?: "")
        val isSignedIn = user != null
        runOnUiThread {
            web.evaluateJavascript("window.onAuthStateChanged && window.onAuthStateChanged($isSignedIn, $email, $uid);", null)
        }
    }

    private fun attemptAutoSignIn() {
        if (Firebase.auth.currentUser != null) {
            startStoreSyncListener()
            return
        }
        val credentialManager = CredentialManager.create(this)
        val clientId = try {
            getString(R.string.default_web_client_id)
        } catch (e: Exception) {
            return
        }

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(true)
            .build()

        val request = GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()

        lifecycleScope.launch {
            try {
                val result = credentialManager.getCredential(this@MainActivity, request)
                val credential = result.credential
                if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                    val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                    Firebase.auth.signInWithCredential(authCredential).await()
                    startStoreSyncListener()
                    notifyAuthState()
                }
            } catch (_: NoCredentialException) {
                // No previously authorized Google credential saved for silent sign-in
            } catch (e: Exception) {
                // Silent auto sign-in not available on clean session
            }
        }
    }

    private fun signInWithGoogle() {
        val credentialManager = CredentialManager.create(this)
        val clientId = try {
            getString(R.string.default_web_client_id)
        } catch (e: Exception) {
            toast("Google Sign-In configuration missing")
            return
        }

        val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(signInOption).build()

        lifecycleScope.launch {
            try {
                val result = credentialManager.getCredential(this@MainActivity, request)
                val credential = result.credential
                if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                    val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                    Firebase.auth.signInWithCredential(authCredential).await()
                    notifyAuthState()
                    startStoreSyncListener()
                    toast("Signed in as ${Firebase.auth.currentUser?.email}")
                }
            } catch (e: GetCredentialCancellationException) {
                Log.w("Auth", "Google Sign-In cancelled: ${e.message}", e)
            } catch (e: NoCredentialException) {
                Log.w("Auth", "No Google credentials available: ${e.message}", e)
                toast("No Google account found on device. Please sign in on your phone.")
            } catch (e: Exception) {
                Log.e("Auth", "Google Sign-In failed", e)
                toast("Sign in failed: ${e.localizedMessage}")
            }
        }
    }

    private fun signOutUser() {
        val credentialManager = CredentialManager.create(this)
        Firebase.auth.signOut()
        storeListener?.remove()
        storeListener = null
        lifecycleScope.launch {
            try {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (e: Exception) {
                Log.e("Auth", "Failed to clear credential state", e)
            }
            notifyAuthState()
            toast("Signed out")
        }
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("No app found to open this link")
        }
    }

    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    // Reads every phone contact that has a number and hands the list to the web app.
    private fun sendContacts() {
        Thread {
            val map = LinkedHashMap<String, JSONObject>()
            val phone = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val cols = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            try {
                contentResolver.query(
                    phone,
                    cols,
                    null,
                    null,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC"
                )?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(1) ?: ""
                        val num = c.getString(2) ?: ""
                        val key = "$name|$num"
                        if (!map.containsKey(key) && (name.isNotBlank() || num.isNotBlank())) {
                            map[key] = JSONObject()
                                .put("name", name)
                                .put("tel", num)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            val arr = JSONArray()
            map.values.forEach { arr.put(it) }
            runOnUiThread {
                web.evaluateJavascript("window.onPhoneContacts(" + arr.toString() + ")", null)
            }
        }.start()
    }

    private fun saveToDownloads(name: String, mime: String, bytes: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Cannot create file")
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloadsDir.mkdirs()
            val file = File(downloadsDir, name)
            file.outputStream().use { it.write(bytes) }
        }
    }

    private fun jsonObjectToMap(jsonObj: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val keys = jsonObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = jsonObj.get(key)
            if (value is JSONObject) {
                map[key] = jsonObjectToMap(value)
            } else if (value is JSONArray) {
                val list = mutableListOf<Any?>()
                for (i in 0 until value.length()) {
                    val item = value.get(i)
                    if (item is JSONObject) list.add(jsonObjectToMap(item)) else list.add(item)
                }
                map[key] = list
            } else if (value == JSONObject.NULL) {
                map[key] = null
            } else {
                map[key] = value
            }
        }
        return map
    }

    private fun mapToJsonObject(map: Map<String, Any?>): JSONObject {
        val json = JSONObject()
        for ((k, v) in map) {
            when (v) {
                is Map<*, *> -> @Suppress("UNCHECKED_CAST") json.put(k, mapToJsonObject(v as Map<String, Any?>))
                is List<*> -> {
                    val arr = JSONArray()
                    for (item in v) {
                        if (item is Map<*, *>) {
                            @Suppress("UNCHECKED_CAST")
                            arr.put(mapToJsonObject(item as Map<String, Any?>))
                        } else {
                            arr.put(item ?: JSONObject.NULL)
                        }
                    }
                    json.put(k, arr)
                }
                null -> json.put(k, JSONObject.NULL)
                is com.google.firebase.Timestamp -> json.put(k, v.toDate().time)
                else -> json.put(k, v)
            }
        }
        return json
    }

    private fun getBiometricStatus(): String {
        return try {
            val biometricManager = BiometricManager.from(this)
            val authenticators = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
            when (biometricManager.canAuthenticate(authenticators)) {
                BiometricManager.BIOMETRIC_SUCCESS -> "available"
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "no_hardware"
                BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "hw_unavailable"
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "none_enrolled"
                else -> "unsupported"
            }
        } catch (e: Exception) {
            "unsupported"
        }
    }

    private fun authenticateWithBiometrics(title: String, subtitle: String) {
        runOnUiThread {
            try {
                val biometricManager = BiometricManager.from(this)
                val authenticators = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
                val canAuth = biometricManager.canAuthenticate(authenticators)
                if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
                    val status = when (canAuth) {
                        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "no_hardware"
                        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "hw_unavailable"
                        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "none_enrolled"
                        else -> "unsupported"
                    }
                    web.evaluateJavascript("window.onBiometricResult && window.onBiometricResult(false, '$status');", null)
                    return@runOnUiThread
                }

                val executor = ContextCompat.getMainExecutor(this)
                val callback = object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        runOnUiThread {
                            web.evaluateJavascript("window.onBiometricResult && window.onBiometricResult(true, 'success');", null)
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        val errEscaped = JSONObject.quote(errString.toString())
                        runOnUiThread {
                            web.evaluateJavascript("window.onBiometricResult && window.onBiometricResult(false, $errEscaped);", null)
                        }
                    }

                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        runOnUiThread {
                            web.evaluateJavascript("window.onBiometricFailed && window.onBiometricFailed();", null)
                        }
                    }
                }

                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(if (title.isNotBlank()) title else "Biometric Verification")
                    .setSubtitle(if (subtitle.isNotBlank()) subtitle else "Confirm your fingerprint or face to proceed")
                    .setAllowedAuthenticators(authenticators)
                    .build()

                val prompt = BiometricPrompt(this, executor, callback)
                prompt.authenticate(promptInfo)
            } catch (e: Exception) {
                Log.e("Biometric", "Error showing biometric prompt", e)
                val errQuote = JSONObject.quote(e.message ?: "Authentication error")
                web.evaluateJavascript("window.onBiometricResult && window.onBiometricResult(false, $errQuote);", null)
            }
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun checkBiometricStatus(): String {
            return getBiometricStatus()
        }

        @JavascriptInterface
        fun promptBiometric(title: String, subtitle: String) {
            authenticateWithBiometrics(title, subtitle)
        }
        @JavascriptInterface
        fun pickContacts() {
            runOnUiThread {
                if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
                    sendContacts()
                else
                    contactsPermission.launch(Manifest.permission.READ_CONTACTS)
            }
        }

        @JavascriptInterface
        fun pickSingleContact() {
            runOnUiThread {
                try {
                    val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                    singleContactPicker.launch(intent)
                } catch (e: Exception) {
                    try {
                        val intent = Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI)
                        singleContactPicker.launch(intent)
                    } catch (e2: Exception) {
                        toast("Could not open contacts picker")
                    }
                }
            }
        }

        @JavascriptInterface
        fun openNewContactIntent(name: String, phone: String) {
            runOnUiThread {
                try {
                    val intent = Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI).apply {
                        putExtra(ContactsContract.Intents.Insert.NAME, name)
                        putExtra(ContactsContract.Intents.Insert.PHONE, phone)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    toast("Cannot open contacts app")
                }
            }
        }

        @JavascriptInterface
        fun saveFile(name: String, base64: String, mime: String) {
            runOnUiThread {
                try {
                    val cleanBase64 = if (base64.contains(",")) base64.substringAfter(",") else base64
                    saveToDownloads(name, mime, Base64.decode(cleanBase64, Base64.DEFAULT))
                    toast("Saved in Downloads: $name")
                } catch (e: Exception) {
                    Log.e("SaveFile", "Error saving file $name", e)
                    toast("Could not save $name: ${e.localizedMessage}")
                }
            }
        }

        @JavascriptInterface
        fun shareFile(name: String, base64: String, mime: String) {
            runOnUiThread {
                try {
                    val cleanBase64 = if (base64.contains(",")) base64.substringAfter(",") else base64
                    val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                    val dir = File(cacheDir, "backups").apply { mkdirs() }
                    val file = File(dir, name)
                    file.outputStream().use { it.write(bytes) }
                    val uri = FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = if (mime.isNotBlank()) mime else "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "Backup: $name")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Share or Save Backup"))
                } catch (e: Exception) {
                    Log.e("ShareFile", "Error sharing file $name", e)
                    toast("Could not share file: ${e.localizedMessage}")
                }
            }
        }

        private fun isPackageInstalled(pkg: String): Boolean {
            return try {
                packageManager.getPackageInfo(pkg, 0)
                true
            } catch (_: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun isWhatsAppInstalled(): Boolean {
            return isPackageInstalled("com.whatsapp") || isPackageInstalled("com.whatsapp.w4b")
        }

        @JavascriptInterface
        fun isWhatsAppBusinessInstalled(): Boolean {
            return isPackageInstalled("com.whatsapp.w4b")
        }

        @JavascriptInterface
        fun getInstalledWhatsAppType(): String {
            val reg = isPackageInstalled("com.whatsapp")
            val biz = isPackageInstalled("com.whatsapp.w4b")
            return when {
                reg && biz -> "both"
                biz -> "w4b"
                reg -> "whatsapp"
                else -> "none"
            }
        }

        private fun normalizeWhatsAppPhone(phone: String): String {
            val raw = phone.trim()
            val digits = raw.replace(Regex("\\D"), "")
            if (digits.isEmpty()) return ""
            if (raw.startsWith("+")) return digits
            if (digits.startsWith("00")) return digits.substring(2)
            if (digits.startsWith("0")) return "92" + digits.substring(1)
            if (digits.length == 10 && !digits.startsWith("92")) return "92$digits"
            return digits
        }

        @JavascriptInterface
        fun openWhatsApp(phone: String, message: String): Boolean {
            return openWhatsAppWithTarget(phone, message, "")
        }

        @JavascriptInterface
        fun getInstalledWhatsAppInfo(): String {
            val biz = isPackageInstalled("com.whatsapp.w4b")
            val reg = isPackageInstalled("com.whatsapp")
            val json = JSONObject().apply {
                put("hasBusiness", biz)
                put("hasRegular", reg)
                put("installedCount", (if (biz) 1 else 0) + (if (reg) 1 else 0))
            }
            return json.toString()
        }

        @JavascriptInterface
        fun openWhatsAppWithTarget(phone: String, message: String, targetApp: String): Boolean {
            val cleanPhone = normalizeWhatsAppPhone(phone)
            if (cleanPhone.isEmpty()) {
                runOnUiThread { toast("Please enter a customer phone number") }
                return false
            }
            runOnUiThread {
                try {
                    val encodedMsg = Uri.encode(message)
                    val uri = "whatsapp://send?phone=$cleanPhone&text=$encodedMsg".toUri()

                    val hasBiz = isPackageInstalled("com.whatsapp.w4b")
                    val hasReg = isPackageInstalled("com.whatsapp")
                    val normalizedTarget = targetApp.lowercase().trim()

                    if (normalizedTarget in listOf("w4b", "business", "biz", "acc1", "com.whatsapp.w4b")) {
                        if (hasBiz) {
                            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                setPackage("com.whatsapp.w4b")
                            }
                            startActivity(intent)
                            return@runOnUiThread
                        } else if (hasReg) {
                            toast("WhatsApp Business not found. Opening WhatsApp...")
                            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                setPackage("com.whatsapp")
                            }
                            startActivity(intent)
                            return@runOnUiThread
                        }
                    } else if (normalizedTarget in listOf("whatsapp", "personal", "reg", "acc2", "acc3", "dual", "com.whatsapp")) {
                        if (hasReg) {
                            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                setPackage("com.whatsapp")
                            }
                            startActivity(intent)
                            return@runOnUiThread
                        } else if (hasBiz) {
                            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                setPackage("com.whatsapp.w4b")
                            }
                            startActivity(intent)
                            return@runOnUiThread
                        }
                    }

                    // For "ask", default/unspecified, or both installed:
                    val candidateIntents = mutableListOf<Intent>()
                    if (hasBiz) {
                        candidateIntents.add(Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.whatsapp.w4b") })
                    }
                    if (hasReg) {
                        candidateIntents.add(Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.whatsapp") })
                    }

                    if (candidateIntents.size == 1) {
                        startActivity(candidateIntents[0])
                    } else if (candidateIntents.size > 1) {
                        val primary = candidateIntents.removeAt(0) // WhatsApp Business first
                        val chooser = Intent.createChooser(primary, "Send invoice via WhatsApp").apply {
                            putExtra(Intent.EXTRA_INITIAL_INTENTS, candidateIntents.toTypedArray())
                        }
                        startActivity(chooser)
                    } else {
                        val webUri = "https://api.whatsapp.com/send?phone=$cleanPhone&text=$encodedMsg".toUri()
                        startActivity(Intent(Intent.ACTION_VIEW, webUri))
                    }
                } catch (e: Exception) {
                    Log.e("WhatsApp", "Failed to open customer WhatsApp", e)
                    toast("Could not open WhatsApp: ${e.localizedMessage}")
                }
            }
            return true
        }

        @JavascriptInterface
        fun shareImage(base64Data: String, filename: String, phone: String, caption: String, directWhatsApp: Boolean) {
            shareImageWithTarget(base64Data, filename, phone, caption, directWhatsApp, "")
        }

        @JavascriptInterface
        fun shareImageWithTarget(
            base64Data: String,
            filename: String,
            phone: String,
            caption: String,
            directWhatsApp: Boolean,
            targetApp: String
        ) {
            runOnUiThread {
                try {
                    val cleanBase64 = if (base64Data.contains(",")) base64Data.substringAfter(",") else base64Data
                    val imageBytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                    val imagesDir = File(cacheDir, "images").apply { mkdirs() }
                    val cleanFileName = if (filename.endsWith(".png")) filename else "$filename.png"
                    val file = File(imagesDir, cleanFileName)
                    file.outputStream().use { it.write(imageBytes) }

                    val uri: Uri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "${packageName}.fileprovider",
                        file
                    )

                    val cleanPhone = normalizeWhatsAppPhone(phone)

                    if (directWhatsApp) {
                        for (pkg in arrayOf("com.whatsapp.w4b", "com.whatsapp")) {
                            try {
                                grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            } catch (_: Exception) {}
                        }

                        try {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clip = ClipData.newUri(contentResolver, "Invoice Image", uri)
                            clipboard?.setPrimaryClip(clip)
                        } catch (_: Exception) {}

                        val hasBiz = isPackageInstalled("com.whatsapp.w4b")
                        val hasReg = isPackageInstalled("com.whatsapp")
                        val normalizedTarget = targetApp.lowercase().trim()

                        fun createDirectIntent(pkg: String): Intent {
                            return Intent(Intent.ACTION_SEND).apply {
                                type = "image/png"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                if (caption.isNotEmpty()) {
                                    putExtra(Intent.EXTRA_TEXT, caption)
                                }
                                if (cleanPhone.isNotEmpty()) {
                                    putExtra("jid", "$cleanPhone@s.whatsapp.net")
                                    putExtra(Intent.EXTRA_PHONE_NUMBER, cleanPhone)
                                }
                                setPackage(pkg)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        }

                        fun createPickerIntent(pkg: String): Intent {
                            return Intent(Intent.ACTION_SEND).apply {
                                type = "image/png"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                if (caption.isNotEmpty()) {
                                    putExtra(Intent.EXTRA_TEXT, caption)
                                }
                                setPackage(pkg)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        }

                        if (normalizedTarget in listOf("w4b", "business", "biz", "acc1", "com.whatsapp.w4b")) {
                            val targetPkg = if (hasBiz) "com.whatsapp.w4b" else if (hasReg) "com.whatsapp" else ""
                            if (targetPkg.isNotEmpty()) {
                                if (targetPkg != "com.whatsapp.w4b" && hasReg) {
                                    toast("WhatsApp Business not found. Opening regular WhatsApp...")
                                }
                                var sent = false
                                if (cleanPhone.isNotEmpty()) {
                                    try {
                                        startActivity(createDirectIntent(targetPkg))
                                        sent = true
                                    } catch (e: Exception) {
                                        Log.w("Share", "Direct JID failed for $targetPkg: ${e.message}")
                                    }
                                }
                                if (!sent) {
                                    startActivity(createPickerIntent(targetPkg))
                                }
                                return@runOnUiThread
                            }
                        } else if (normalizedTarget in listOf("whatsapp", "personal", "reg", "acc2", "acc3", "dual", "com.whatsapp")) {
                            val targetPkg = if (hasReg) "com.whatsapp" else if (hasBiz) "com.whatsapp.w4b" else ""
                            if (targetPkg.isNotEmpty()) {
                                var sent = false
                                if (cleanPhone.isNotEmpty()) {
                                    try {
                                        startActivity(createDirectIntent(targetPkg))
                                        sent = true
                                    } catch (e: Exception) {
                                        Log.w("Share", "Direct JID failed for $targetPkg: ${e.message}")
                                    }
                                }
                                if (!sent) {
                                    startActivity(createPickerIntent(targetPkg))
                                }
                                return@runOnUiThread
                            }
                        }

                        // For "ask", default/unspecified, or both installed:
                        val candidateIntents = mutableListOf<Intent>()
                        if (hasBiz) {
                            candidateIntents.add(createDirectIntent("com.whatsapp.w4b"))
                        }
                        if (hasReg) {
                            candidateIntents.add(createDirectIntent("com.whatsapp"))
                        }

                        if (candidateIntents.size == 1) {
                            var sent = false
                            try {
                                startActivity(candidateIntents[0])
                                sent = true
                            } catch (e: Exception) {
                                Log.w("Share", "Candidate direct failed: ${e.message}")
                            }
                            if (!sent) {
                                val pkg = if (hasBiz) "com.whatsapp.w4b" else "com.whatsapp"
                                startActivity(createPickerIntent(pkg))
                            }
                            return@runOnUiThread
                        } else if (candidateIntents.size > 1) {
                            // Both installed: show chooser containing WhatsApp Business and Regular WhatsApp!
                            val primary = candidateIntents.removeAt(0) // WhatsApp Business is primary
                            val chooser = Intent.createChooser(primary, "Share invoice via WhatsApp").apply {
                                putExtra(Intent.EXTRA_INITIAL_INTENTS, candidateIntents.toTypedArray())
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(chooser)
                            return@runOnUiThread
                        }

                        toast("WhatsApp not found on device. Opening general share options...")
                    }

                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        if (caption.isNotEmpty()) {
                            putExtra(Intent.EXTRA_TEXT, caption)
                        }
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(shareIntent, "Share invoice image"))
                } catch (e: Exception) {
                    Log.e("Share", "Error sharing invoice image", e)
                    toast("Could not share image: ${e.localizedMessage}")
                }
            }
        }

        @JavascriptInterface
        fun signInWithGoogle() {
            runOnUiThread {
                this@MainActivity.signInWithGoogle()
            }
        }

        @JavascriptInterface
        fun signOut() {
            runOnUiThread {
                this@MainActivity.signOutUser()
            }
        }

        @JavascriptInterface
        fun checkAuthState() {
            runOnUiThread {
                notifyAuthState()
                if (Firebase.auth.currentUser != null) {
                    startStoreSyncListener()
                }
            }
        }

        @JavascriptInterface
        fun saveStoreDoc(docId: String, jsonContent: String) {
            val user = Firebase.auth.currentUser
            if (user == null) {
                runOnUiThread {
                    web.evaluateJavascript("window.onFirestoreSyncStatus && window.onFirestoreSyncStatus('not_signed_in');", null)
                }
                return
            }
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val db = FirebaseFirestore.getInstance(getString(R.string.firestore_database_id))
                    val jsonMap = JSONObject(jsonContent)
                    val map = jsonObjectToMap(jsonMap).toMutableMap()
                    map["updatedBy"] = user.uid
                    map["updatedAt"] = FieldValue.serverTimestamp()
                    db.collection("store").document(docId).set(map, SetOptions.merge()).await()
                    withContext(Dispatchers.Main) {
                        web.evaluateJavascript("window.onFirestoreSyncStatus && window.onFirestoreSyncStatus('synced');", null)
                    }
                } catch (e: Exception) {
                    Log.e("Sync", "Error saving store doc $docId", e)
                    val errQuote = JSONObject.quote(e.message ?: "Failed to save")
                    withContext(Dispatchers.Main) {
                        web.evaluateJavascript("window.onFirestoreSyncError && window.onFirestoreSyncError($errQuote);", null)
                    }
                }
            }
        }
    }
}
