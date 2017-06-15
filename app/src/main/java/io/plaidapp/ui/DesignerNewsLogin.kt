/*
 * Copyright 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.plaidapp.ui

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.support.design.widget.Snackbar
import android.support.design.widget.TextInputLayout
import android.support.v4.content.ContextCompat
import android.text.Editable
import android.text.TextWatcher
import android.transition.TransitionManager
import android.util.Log
import android.util.Patterns
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

import com.bumptech.glide.Glide

import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet

import butterknife.BindView
import butterknife.ButterKnife
import io.plaidapp.BuildConfig
import io.plaidapp.R
import io.plaidapp.data.api.designernews.model.AccessToken
import io.plaidapp.data.api.designernews.model.User
import io.plaidapp.data.prefs.DesignerNewsPrefs
import io.plaidapp.ui.transitions.FabTransform
import io.plaidapp.ui.transitions.MorphTransform
import io.plaidapp.util.ScrimUtil
import io.plaidapp.util.glide.CircleTransform
import kotlinx.android.synthetic.main.activity_designer_news_login.*
import kotlinx.android.synthetic.main.infinite_loading.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class DesignerNewsLogin : Activity() {

    internal var isDismissing = false
    internal lateinit var designerNewsPrefs: DesignerNewsPrefs
    private var shouldPromptForPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_designer_news_login)

        if (!FabTransform.setup(this, container)) {
            MorphTransform.setup(this, container,
                    ContextCompat.getColor(this, R.color.background_light),
                    resources.getDimensionPixelSize(R.dimen.dialog_corners))
        }

        loading.visibility = View.GONE
        setupAccountAutocomplete()
        username.addTextChangedListener(loginFieldWatcher)
        // the primer checkbox messes with focus order so force it
        username.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                password.requestFocus()
            }
            actionId == EditorInfo.IME_ACTION_NEXT
        }
        password.addTextChangedListener(loginFieldWatcher)
        password.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE && isLoginValid) {
                login.performClick()
            }
            actionId == EditorInfo.IME_ACTION_NEXT
        }
        designerNewsPrefs = DesignerNewsPrefs.get(this)
    }

    @SuppressLint("NewApi")
    override fun onEnterAnimationComplete() {
        /* Postpone some of the setup steps so that we can run it after the enter transition (if
        there is one). Otherwise we may show the permissions dialog or account dropdown during the
        enter animation which is jarring. */
        if (shouldPromptForPermission) {
            requestPermissions(arrayOf(Manifest.permission.GET_ACCOUNTS),
                    PERMISSIONS_REQUEST_GET_ACCOUNTS)
            shouldPromptForPermission = false
        }
        username.setOnFocusChangeListener { v, hasFocus -> maybeShowAccounts() }
        maybeShowAccounts()
    }

    override fun onBackPressed() {
        dismiss(null)
    }

    @TargetApi(Build.VERSION_CODES.M)
    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>,
                                            grantResults: IntArray) {
        if (requestCode == PERMISSIONS_REQUEST_GET_ACCOUNTS) {
            TransitionManager.beginDelayedTransition(container)
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setupAccountAutocomplete()
                username!!.requestFocus()
                username!!.showDropDown()
            } else {
                // if permission was denied check if we should ask again in the future (i.e. they
                // did not check 'never ask again')
                if (shouldShowRequestPermissionRationale(Manifest.permission.GET_ACCOUNTS)) {
                    setupPermissionPrimer()
                } else {
                    // denied & shouldn't ask again. deal with it (•_•) ( •_•)>⌐■-■ (⌐■_■)
                    TransitionManager.beginDelayedTransition(container)
                    permission_primer.visibility = View.GONE
                }
            }
        }
    }

    fun doLogin(view: View) {
        showLoading()
        getAccessToken()
    }

    fun signup(view: View) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.designernews.co/users/new")))
    }

    fun dismiss(view: View?) {
        isDismissing = true
        setResult(Activity.RESULT_CANCELED)
        finishAfterTransition()
    }

    internal fun maybeShowAccounts() {
        if (username!!.hasFocus()
                && username!!.isAttachedToWindow
                && username!!.adapter != null
                && username!!.adapter.count > 0) {
            username!!.showDropDown()
        }
    }

    internal val isLoginValid: Boolean
        get() = username!!.length() > 0 && password!!.length() > 0

    @SuppressLint("InflateParams")
    internal fun showLoggedInUser() {
        val authedUser = designerNewsPrefs.api.authedUser
        authedUser.enqueue(object : Callback<User> {
            override fun onResponse(call: Call<User>, response: Response<User>) {
                if (!response.isSuccessful) return
                val user = response.body()
                designerNewsPrefs.setLoggedInUser(user)
                val confirmLogin = Toast(applicationContext)
                val v = LayoutInflater.from(this@DesignerNewsLogin).inflate(R.layout
                        .toast_logged_in_confirmation, null, false)
                (v.findViewById(R.id.name) as TextView).text = user.display_name.toLowerCase()
                // need to use app context here as the activity will be destroyed shortly
                Glide.with(applicationContext)
                        .load(user.portrait_url)
                        .placeholder(R.drawable.avatar_placeholder)
                        .transform(CircleTransform(applicationContext))
                        .into(v.findViewById(R.id.avatar) as ImageView)
                v.findViewById(R.id.scrim).background = ScrimUtil
                        .makeCubicGradientScrimDrawable(
                                ContextCompat.getColor(this@DesignerNewsLogin, R.color.scrim),
                                5, Gravity.BOTTOM)
                confirmLogin.view = v
                confirmLogin.setGravity(Gravity.BOTTOM or Gravity.FILL_HORIZONTAL, 0, 0)
                confirmLogin.duration = Toast.LENGTH_LONG
                confirmLogin.show()
            }

            override fun onFailure(call: Call<User>, t: Throwable) {
                Log.e(javaClass.canonicalName, t.message, t)
            }
        })
    }

    internal fun showLoginFailed() {
        Snackbar.make(container!!, R.string.login_failed, Snackbar.LENGTH_SHORT).show()
        showLogin()
        password!!.requestFocus()
    }

    private val loginFieldWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(s: Editable) {
            login.isEnabled = isLoginValid
        }
    }

    private fun showLoading() {
        TransitionManager.beginDelayedTransition(container)
        dialog_title.visibility = View.GONE
        username_float_label.visibility = View.GONE
        permission_primer.visibility = View.GONE
        password_float_label.visibility = View.GONE
        actions_container.visibility = View.GONE
        loading!!.visibility = View.VISIBLE
    }

    private fun showLogin() {
        TransitionManager.beginDelayedTransition(container)
        dialog_title.visibility = View.VISIBLE
        username_float_label.visibility = View.VISIBLE
        password_float_label.visibility = View.VISIBLE
        actions_container.visibility = View.VISIBLE
        loading!!.visibility = View.GONE
    }

    private fun getAccessToken() {
        val login = designerNewsPrefs.api.login(
                buildLoginParams(username!!.text.toString(), password!!.text.toString()))
        login.enqueue(object : Callback<AccessToken> {
            override fun onResponse(call: Call<AccessToken>, response: Response<AccessToken>) {
                if (response.isSuccessful) {
                    designerNewsPrefs.setAccessToken(this@DesignerNewsLogin, response.body().access_token)
                    showLoggedInUser()
                    setResult(Activity.RESULT_OK)
                    finish()
                } else {
                    showLoginFailed()
                }
            }

            override fun onFailure(call: Call<AccessToken>, t: Throwable) {
                Log.e(javaClass.canonicalName, t.message, t)
                showLoginFailed()
            }
        })
    }

    private fun buildLoginParams(username: String, password: String): Map<String, String> {
        val loginParams = HashMap<String, String>(5)
        loginParams.put("client_id", BuildConfig.DESIGNER_NEWS_CLIENT_ID)
        loginParams.put("client_secret", BuildConfig.DESIGNER_NEWS_CLIENT_SECRET)
        loginParams.put("grant_type", "password")
        loginParams.put("username", username)
        loginParams.put("password", password)
        return loginParams
    }

    @SuppressLint("NewApi")
    private fun setupAccountAutocomplete() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.GET_ACCOUNTS) == PackageManager.PERMISSION_GRANTED) {
            permission_primer.visibility = View.GONE
            val accounts = AccountManager.get(this).accounts
            val emailSet = accounts
                    .filter { Patterns.EMAIL_ADDRESS.matcher(it.name).matches() }
                    .map { it.name }
                    .toSet()
            username.setAdapter(ArrayAdapter(this,
                    R.layout.account_dropdown_item, ArrayList(emailSet)))
        } else {
            if (shouldShowRequestPermissionRationale(Manifest.permission.GET_ACCOUNTS)) {
                setupPermissionPrimer()
            } else {
                permission_primer.visibility = View.GONE
                shouldPromptForPermission = true
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    private fun setupPermissionPrimer() {
        permission_primer.isChecked = false
        permission_primer.visibility = View.VISIBLE
        permission_primer.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                requestPermissions(arrayOf(Manifest.permission.GET_ACCOUNTS),
                        PERMISSIONS_REQUEST_GET_ACCOUNTS)
            }
        }
    }

    companion object {
        private val PERMISSIONS_REQUEST_GET_ACCOUNTS = 0
    }
}
