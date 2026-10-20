package tv.cleanyour.core.trust

import tv.cleanyour.core.model.LauncherArtifact
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.store.BlobStore

// Trust anchors compiled into the app. Changing one is a reviewed code change, never a data file.
object TrustRoots {
    // Root of the module index chain: signs keys.json only (KeysFile). DEVELOPMENT key, generated
    // 2026-10-19 on jarvis; private half in /Users/dev/jarvis/secrets/cleanyourtv-index/. It and
    // the index signing key must be rotated before any public release (DECISIONS-W5.md, K2).
    const val INDEX_ROOT_PUBLIC_KEY = "RWSpmaqVvy157kGBXvTnhG5vqBi+yTHcO1M8r6aUJH+k/g5kyVJr9B6I"   // key id EE792DBF95AA99A9

    // The sequence of the keys.json bundled with this build: an older keys file is refused.
    const val BUNDLED_KEYS_SEQUENCE = 1L

    val indexRoot: MinisignKey get() = Ed25519Minisign.parsePublicKey(INDEX_ROOT_PUBLIC_KEY)

    fun indexVerifier(): MinisignIndexVerifier = MinisignIndexVerifier(indexRoot, keysFloor = BUNDLED_KEYS_SEQUENCE)
}

// The launcher APK bundled in the phone app (D13, Q1): upstream github.com/Gathin23/cleanyourtv at
// commit 1bbf0c9 (third_party/cleanyourtv), `assembleRelease`, re-signed with the dedicated
// launcher release key by scripts/build-launcher-on-forge.sh. The verifyLauncherPin task (part of
// :core:trust check and of :android:platform preBuild) fails the build when the asset differs.
object PinnedLauncher {
    const val ASSET_NAME = "cleanyourtv-launcher.apk"
    const val UPSTREAM_COMMIT = "1bbf0c939fa7d1c0ed91c5fec37ddb57e749a3fd"
    const val PACKAGE = "com.gathin.tvhome"
    const val VERSION_CODE = 2L
    const val VERSION_NAME = "2.0"
    const val SIZE = 270_885L
    const val SHA256 = "26461a1fd802e6f2f3ec2200171dd9bac2cfafe069cc07754464d903216a6897"
    // SHA-256 of the signing certificate (DER), "O=cleanyourtv, CN=Clean Your TV launcher", v2 and v3.
    const val SIGNER_SHA256 = "576418154bec6505bfc709ccaa6ae85b6e09d98eb89f4a25147ca1b4b0505132"
}

// The TV curtain bundled in the phone app (RFC-CURTAIN-1): module :tv:curtain of this repo, built
// twice and compared, then signed with the launcher release key by scripts/build-curtain-on-forge.sh.
// One signer to pin: SIGNER_SHA256 is the launcher's. The verifyCurtainPin task (part of :core:trust
// check and of :android:platform preBuild) fails the build when the asset differs.
object PinnedCurtain {
    const val ASSET_NAME = "cleanyourtv-curtain.apk"
    const val PACKAGE = "tv.cleanyour.curtain"
    const val ACTIVITY = ".CurtainActivity"
    const val RECEIVER = ".CueReceiver"
    const val VERSION_CODE = 1L
    const val VERSION_NAME = "0.1"
    const val SIZE = 49_571L
    const val SHA256 = "d114a60ee0eb7b52b16646bdedfdac15af1a0ef47b15fa88af35c7aaa6772215"
    const val SIGNER_SHA256 = PinnedLauncher.SIGNER_SHA256
}

// LauncherPin over the BlobStore's bundled asset. bundled() refuses an asset whose bytes are not the
// pinned ones, so a swapped asset can never be offered as the launcher.
class BundledLauncherPin(private val blobs: BlobStore) : LauncherPin {
    override fun bundled(): LauncherArtifact {
        val blob = blobs.bundled(PinnedLauncher.ASSET_NAME)
        check(blob.sha256.hex == PinnedLauncher.SHA256 && blob.size == PinnedLauncher.SIZE) {
            "bundled launcher does not match its pin (sha256 ${blob.sha256.hex}, ${blob.size} bytes)"
        }
        return LauncherArtifact(
            pkg = PackageName(PinnedLauncher.PACKAGE),
            versionCode = PinnedLauncher.VERSION_CODE,
            versionName = PinnedLauncher.VERSION_NAME,
            sha256 = Sha256(PinnedLauncher.SHA256),
            signer = Sha256(PinnedLauncher.SIGNER_SHA256),
            blob = blob,
        )
    }
}

// CurtainPin over the BlobStore's bundled asset, refusing an asset whose bytes are not the pinned ones.
class BundledCurtainPin(private val blobs: BlobStore) : CurtainPin {
    override fun bundled(): LauncherArtifact {
        val blob = blobs.bundled(PinnedCurtain.ASSET_NAME)
        check(blob.sha256.hex == PinnedCurtain.SHA256 && blob.size == PinnedCurtain.SIZE) {
            "bundled curtain does not match its pin (sha256 ${blob.sha256.hex}, ${blob.size} bytes)"
        }
        return LauncherArtifact(
            pkg = PackageName(PinnedCurtain.PACKAGE),
            versionCode = PinnedCurtain.VERSION_CODE,
            versionName = PinnedCurtain.VERSION_NAME,
            sha256 = Sha256(PinnedCurtain.SHA256),
            signer = Sha256(PinnedCurtain.SIGNER_SHA256),
            blob = blob,
        )
    }
}
