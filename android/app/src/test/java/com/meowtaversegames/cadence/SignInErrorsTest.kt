package com.meowtaversegames.cadence

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.firestore.FirebaseFirestoreException
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class SignInErrorsTest {
    @Test fun invalidCredentialsUseWindowsWording() {
        for (code in listOf("ERROR_INVALID_CREDENTIAL", "ERROR_INVALID_LOGIN_CREDENTIALS", "ERROR_USER_NOT_FOUND",
            "ERROR_WRONG_PASSWORD", "EMAIL_NOT_FOUND", "INVALID_PASSWORD")) {
            assertEquals("メールアドレスまたはパスワードが違います。", signInErrorMessage(code))
        }
    }
    @Test fun inputErrorsUseWindowsWording() {
        assertEquals("メールアドレスの形式を確認してください。", signInErrorMessage("ERROR_INVALID_EMAIL"))
        assertEquals("パスワードを入力してください。", signInErrorMessage("ERROR_MISSING_PASSWORD"))
    }
    @Test fun throttlingAndAccountErrorsUseWindowsWording() {
        for (code in listOf("ERROR_TOO_MANY_REQUESTS", "TOO_MANY_ATTEMPTS_TRY_LATER")) {
            assertEquals("試行回数が多すぎます。しばらく待ってください。", signInErrorMessage(code))
        }
        assertEquals("このアカウントは無効です。", signInErrorMessage("ERROR_USER_DISABLED"))
        assertEquals("メールとパスワードでの認証が許可されていません。", signInErrorMessage("ERROR_OPERATION_NOT_ALLOWED"))
    }
    @Test fun unknownAndNetworkErrorsNeverExposeExceptionText() {
        val expected = "サインインできません。接続や設定を確認してください。"
        assertEquals(expected, signInErrorMessage(null))
        assertEquals(expected, signInErrorMessage("UNKNOWN"))
        assertEquals(expected, signInErrorMessage(signInErrorCode(mock(FirebaseNetworkException::class.java))))
    }
    @Test fun sdkExceptionTypeAndCodeAreMapped() {
        assertEquals("ERROR_TOO_MANY_REQUESTS", signInErrorCode(mock(FirebaseTooManyRequestsException::class.java)))
        val failure = mock(FirebaseAuthException::class.java)
        `when`(failure.errorCode).thenReturn("ERROR_USER_DISABLED")
        assertEquals("ERROR_USER_DISABLED", signInErrorCode(failure))
    }
    @Test fun wrappedInvalidUserStopsListenerRetries() {
        val error = mock(FirebaseFirestoreException::class.java)
        val cause = mock(FirebaseAuthInvalidUserException::class.java)
        `when`(error.cause).thenReturn(cause)
        assertEquals("UNAUTHENTICATED", listenerErrorCode(error))
        assertFalse(listenerFailure(listenerErrorCode(error)).retry)
    }
    @Test fun ordinaryListenerErrorsKeepTheirSdkCode() {
        val error = mock(FirebaseFirestoreException::class.java)
        `when`(error.code).thenReturn(FirebaseFirestoreException.Code.PERMISSION_DENIED)
        assertEquals("PERMISSION_DENIED", listenerErrorCode(error))
    }

}
