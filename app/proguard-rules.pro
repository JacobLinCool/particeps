# The signed protocol permits only X25519/HKDF-SHA256/AES-256-GCM HPKE keys.
# These Conscrypt X-Wing classes are optional Tink implementation paths and are unreachable.
-dontwarn org.conscrypt.HpkeContextRecipient
-dontwarn org.conscrypt.HpkeContextSender
-dontwarn org.conscrypt.HpkeSuite
-dontwarn org.conscrypt.XdhKeySpec
