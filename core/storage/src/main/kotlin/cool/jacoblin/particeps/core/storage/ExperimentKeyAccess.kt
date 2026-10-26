package cool.jacoblin.particeps.core.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Platform boundary; the store owns handle caching and re-queries on every cold open. */
internal interface ExperimentKeyAccess {
    fun existingKey(): SecretKey?
    fun createKey(): SecretKey
    fun deleteKey()
}

internal class AndroidExperimentKeyAccess(private val alias: String) : ExperimentKeyAccess {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun existingKey(): SecretKey? = store.getKey(alias, null) as? SecretKey

    override fun createKey(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        .apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()

    override fun deleteKey() {
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }
}
