package com.infinity.comicreader.ai

/** A step of AI processing; pages go through the chosen steps in this order. */
enum class AiStep(val key: String) {
    /** Removes compression artifacts (JPEG blocks and ringing), at 1x. */
    ARTIFACTS("artifacts"),

    /** Removes halftone screens (the dots of print), at 1x. */
    DESCREEN("descreen"),

    /** Enlarges the page. */
    UPSCALE("upscale")
}

/**
 * Whose models these are and the terms they are used under, as shown in About: [title], by
 * [author], under [license], from [url]; [texts] are the license files under
 * `assets/licenses/`. Their files download from [repository]; [conversion], where set, says
 * the files are a conversion made for the app and where its source is.
 */
enum class ModelSource(
    val title: String,
    val author: String,
    val license: String,
    val url: String,
    val texts: List<String>,
    val repository: String = ModelCatalog.REPOSITORY,
    val conversion: String? = null
) {
    OPENCOMIC(
        "OpenComic AI", "Oleguer Llopart Mora", "CC BY 4.0", "https://github.com/ollm/opencomic-ai-training",
        listOf("opencomic-ai-models.txt", "cc-by-4.0.txt")
    ),
    REAL_ESRGAN(
        "Real-ESRGAN", "Xintao Wang; ncnn conversion by Xintao Wang and nihui", "BSD 3-Clause License", "https://github.com/xinntao/Real-ESRGAN",
        listOf("real-esrgan.txt")
    ),
    WAIFU2X(
        "waifu2x", "nagadomi; ncnn conversion by nihui", "MIT License", "https://github.com/nagadomi/waifu2x",
        listOf("waifu2x.txt")
    ),
    HELAMAN(
        "Helaman's models", "Philip Hofmann (Helaman)", "CC BY 4.0", "https://github.com/Phhofm/models",
        listOf("cc-by-4.0.txt")
    ),
    CUNNY(
        "CuNNy", "funnyplanter", "LGPL 3.0 or later", "https://github.com/funnyplanter/CuNNy",
        listOf("cunny.txt", "lgpl-3.0.txt", "gpl-3.0.txt"), ModelCatalog.HOSTED_REPOSITORY,
        "An ncnn conversion of the mpv shader CuNNy-8x32-DS, made for this app: the same weights, with RGB in and out " +
            "and edges repeated before every layer. Its source, the unmodified shader and the conversion scripts, is " +
            "in the same repository at the same revision."
    ),
    ARTCNN(
        "ArtCNN", "João Chrisóstomo (Artoriuz)", "MIT License", "https://github.com/Artoriuz/ArtCNN",
        listOf("artcnn.txt"), ModelCatalog.HOSTED_REPOSITORY,
        "An ncnn conversion of the ONNX release of ArtCNN C4F32 DS, made for this app: the same weights, with RGB in " +
            "and out. The original and the conversion scripts are in the same repository at the same revision."
    ),
    ANIME4K(
        "Anime4K", "bloc97", "MIT License", "https://github.com/bloc97/Anime4K",
        listOf("anime4k.txt"), ShaderCatalog.REPOSITORY
    );

    /** The catalog's models from this source, shader chains included. */
    val models: List<AiModel> get() = ModelCatalog.all.filter { it.source == this } + ShaderCatalog.all.filter { it.source == this }
}

/** What a model does to a page, in a few words shown beside it where it is picked ([CatalogModel.blurb], [ShaderModel.blurb]). */
enum class ModelBlurb {
    WAIFU2X,
    WAIFU2X_DENOISE,
    OPENCOMIC_UPSCALE,
    REAL_ESRGAN_ANIME,
    DESCREEN_V2,
    DESCREEN_V3,
    ARTIFACT_REMOVAL,
    LSDIR,
    CUNNY,
    ARTCNN,
    ANIME4K_A,
    ANIME4K_B,
    ANIME4K_C,
    ANIME4K_UPSCALE,
    ANIME4K_UPSCALE_DENOISE
}

/** One file of a model: stored as [name], downloaded from [url], [bytes] long with SHA-256 [sha256] (lowercase hex). */
data class ModelFile(val name: String, val url: String, val bytes: Long, val sha256: String)

/** Files downloaded together into one folder of [ModelStore], named by [id]: [files], fetched in order. */
interface FileSet {
    val id: String
    val files: List<ModelFile>

    /** Bytes to download. */
    val bytes: Long get() = files.sumOf { it.bytes }
}

/**
 * A model the app offers to download: [name] for people, run at [step], [scale] times larger
 * out than in, from [source]. Its ncnn files are [param] and [bin]. [pageMs]: its time for a
 * synthetic 1000 x 1500 page on the reference tablet's GPU, at its own scale, started cool.
 * [blurb]: what it does, where known.
 */
data class CatalogModel(
    override val id: String,
    override val name: String,
    override val step: AiStep,
    override val scale: Int,
    val source: ModelSource,
    val param: ModelFile,
    val bin: ModelFile,
    override val pageMs: Long = 0,
    val blurb: ModelBlurb? = null
) : AiModel, FileSet {
    override val files: List<ModelFile> get() = listOf(param, bin)

    /** Bytes to download. */
    override val bytes: Long get() = param.bytes + bin.bytes

    override val fingerprint: String get() = "${param.sha256.take(16)}:${bin.sha256.take(16)}"
}

/**
 * A model the app runs for itself, not on pages: [name] for people, its ncnn files [param] and
 * [bin], downloaded into [ModelStore] like the catalog's models.
 */
data class ToolModel(override val id: String, val name: String, val param: ModelFile, val bin: ModelFile) : FileSet {
    override val files: List<ModelFile> get() = listOf(param, bin)
}

/**
 * The models the app offers: ncnn conversions that load and run in the tile engine
 * ([Sessions]) and, started cool (thermal headroom below 0.7), take a synthetic 1000 x 1500
 * page through in at most [MAX_PAGE_MS] on the reference tablet's GPU ([CatalogModel.pageMs];
 * warm, up to about 1.5 times as long, within [PageLimits.DEFAULT_TIMEOUT_MS]; in the reader, at
 * background priority, a page may take [ReaderAi.WARM] times as long), under licenses
 * that let a free app download and run them (with attribution in About). All are fetched unchanged from
 * a pinned revision ([ModelSource.repository]): OpenComic AI's model repository, or, for
 * conversions made for the app, [HOSTED_REPOSITORY]; so their checksums hold. Each step lists
 * its models as pickers show them: the default first, the faithful before the enhancing, the
 * slow ([CatalogModel.slow]) last.
 */
object ModelCatalog {
    /** The repository the files come from, and the commit they are fetched at. */
    const val REPOSITORY = "https://github.com/ollm/opencomic-ai-models"
    const val COMMIT = "334cc9806f22110633989fe2e8f341872dfa5477"
    const val BASE = "https://raw.githubusercontent.com/ollm/opencomic-ai-models/$COMMIT/models/"

    /** The repository of conversions made for the app, with their sources, and the revision they are fetched at. */
    const val HOSTED_REPOSITORY = "https://huggingface.co/MadameMinty/Emanata"
    const val HOSTED_REVISION = "fdc7f69cbfa9e9b134970ce675259d114e1cb31b"
    const val HOSTED_BASE = "$HOSTED_REPOSITORY/resolve/$HOSTED_REVISION/"

    private fun file(path: String, bytes: Long, sha256: String) = ModelFile(path.substringAfterLast('/'), BASE + path, bytes, sha256)

    private fun hosted(path: String, bytes: Long, sha256: String) = ModelFile(path.substringAfterLast('/'), HOSTED_BASE + path, bytes, sha256)

    val all: List<CatalogModel> = listOf(
        CatalogModel(
            "opencomic-ai-artifact-removal-compact-v2", "OpenComic AI Artifact Removal Fast v2", AiStep.ARTIFACTS, 1, ModelSource.OPENCOMIC,
            file("artifact-removal/models/opencomic-ai-artifact-removal-compact-v2.param", 2875, "0c40c98b871c693facbd34d01f44753ef25a00bd76d4b150b3983a16307d178e"),
            file("artifact-removal/models/opencomic-ai-artifact-removal-compact-v2.bin", 1195348, "319f91018d86181803ca9b51bbaeb954519f20d5028c9f6236b8010f4b91c500"),
            pageMs = 5_100,
            blurb = ModelBlurb.ARTIFACT_REMOVAL
        ),
        CatalogModel(
            "opencomic-ai-descreen-hard-compact-v2", "OpenComic AI Descreen Hard Fast v2", AiStep.DESCREEN, 1, ModelSource.OPENCOMIC,
            file("descreen/models/opencomic-ai-descreen-hard-compact-v2.param", 2875, "0c40c98b871c693facbd34d01f44753ef25a00bd76d4b150b3983a16307d178e"),
            file("descreen/models/opencomic-ai-descreen-hard-compact-v2.bin", 1195348, "a23ba0d45fb6497479c9c2a74ae3a032bf4ea75439b4bb72aabef92bbfba674c"),
            pageMs = 5_100,
            blurb = ModelBlurb.DESCREEN_V2
        ),
        CatalogModel(
            "opencomic-ai-descreen-hard-fast-v3-256", "OpenComic AI Descreen Hard Fast v3", AiStep.DESCREEN, 1, ModelSource.OPENCOMIC,
            file("descreen/models/opencomic-ai-descreen-hard-fast-v3-256.param", 22760, "6d3bdd193effcf56ab5bde4ae09ebb25d914bd14484b97fbdd2719142dda5d54"),
            file("descreen/models/opencomic-ai-descreen-hard-fast-v3-256.bin", 1426400, "35689df85b1a56bbd248f6b2f1aec3bce7fdca6aaa97c4937e7fc1c2bdf95abb"),
            pageMs = 15_400,
            blurb = ModelBlurb.DESCREEN_V3
        ),
        CatalogModel(
            "waifu2x-upconv7-2x", "waifu2x UpConv7 2×", AiStep.UPSCALE, 2, ModelSource.WAIFU2X,
            file("upscale/waifu2x/models-upconv_7_anime_style_art_rgb/scale2.0x_model.param", 1047, "413195ffde05b4d43807792c6c020c916cecdf25dcf002ee83f5e28d5cc246c6"),
            file("upscale/waifu2x/models-upconv_7_anime_style_art_rgb/scale2.0x_model.bin", 1106248, "27d880903ff81390a3b4a900b67af3381c465279e34297546c9a58781855f7ef"),
            pageMs = 3_400,
            blurb = ModelBlurb.WAIFU2X
        ),
        CatalogModel(
            "waifu2x-upconv7-2x-denoise0", "waifu2x UpConv7 2×, denoise 0", AiStep.UPSCALE, 2, ModelSource.WAIFU2X,
            file("upscale/waifu2x/models-upconv_7_anime_style_art_rgb/noise0_scale2.0x_model.param", 1047, "413195ffde05b4d43807792c6c020c916cecdf25dcf002ee83f5e28d5cc246c6"),
            file("upscale/waifu2x/models-upconv_7_anime_style_art_rgb/noise0_scale2.0x_model.bin", 1106248, "e1d2c4d91be98e72c0d48d08626cbe7d879aa2b80f740133dcc0d860eabb4189"),
            pageMs = 3_400,
            blurb = ModelBlurb.WAIFU2X_DENOISE
        ),
        CatalogModel(
            "waifu2x-upconv7-2x-denoise1", "waifu2x UpConv7 2×, denoise 1", AiStep.UPSCALE, 2, ModelSource.WAIFU2X,
            file("upscale/waifu2x/models-upconv_7_anime_style_art_rgb/noise1_scale2.0x_model.param", 1047, "413195ffde05b4d43807792c6c020c916cecdf25dcf002ee83f5e28d5cc246c6"),
            file("upscale/waifu2x/models-upconv_7_anime_style_art_rgb/noise1_scale2.0x_model.bin", 1106248, "536fd7070b023463d30bd105f675acf301760dce539f2fb974bd06906045514f"),
            pageMs = 3_400,
            blurb = ModelBlurb.WAIFU2X_DENOISE
        ),
        CatalogModel(
            "waifu2x-upconv7-2x-denoise2", "waifu2x UpConv7 2×, denoise 2", AiStep.UPSCALE, 2, ModelSource.WAIFU2X,
            file("upscale/waifu2x/models-upconv_7_anime_style_art_rgb/noise2_scale2.0x_model.param", 1047, "413195ffde05b4d43807792c6c020c916cecdf25dcf002ee83f5e28d5cc246c6"),
            file("upscale/waifu2x/models-upconv_7_anime_style_art_rgb/noise2_scale2.0x_model.bin", 1106248, "435f9082425c4520d3586757411ac409b1d67e428ed37ec571feb06f823e245e"),
            pageMs = 2_700,
            blurb = ModelBlurb.WAIFU2X_DENOISE
        ),
        CatalogModel(
            "waifu2x-upconv7-2x-denoise3", "waifu2x UpConv7 2×, denoise 3", AiStep.UPSCALE, 2, ModelSource.WAIFU2X,
            file("upscale/waifu2x/models-upconv_7_anime_style_art_rgb/noise3_scale2.0x_model.param", 1047, "413195ffde05b4d43807792c6c020c916cecdf25dcf002ee83f5e28d5cc246c6"),
            file("upscale/waifu2x/models-upconv_7_anime_style_art_rgb/noise3_scale2.0x_model.bin", 1106248, "3e783a9117d4564fbd0cc376375b29d7eb05bd2e457f748898e82eefb1ace2f1"),
            pageMs = 2_700,
            blurb = ModelBlurb.WAIFU2X_DENOISE
        ),
        CatalogModel(
            "opencomic-ai-upscale-2x-compact-v2", "OpenComic AI Upscale Fast v2 2×", AiStep.UPSCALE, 2, ModelSource.OPENCOMIC,
            file("upscale/models/opencomic-ai-upscale-2x-compact-v2.param", 2974, "7f14c50fc5a5ca3bd2c46aad97cd0cb0ca5d56e7774b12fbd7d90988fbfdd6c9"),
            file("upscale/models/opencomic-ai-upscale-2x-compact-v2.bin", 1205752, "f6266cfb0582a60e537bf157bd5a46d8aa022b7e72b098edffe589b7b6e322c0"),
            pageMs = 5_500,
            blurb = ModelBlurb.OPENCOMIC_UPSCALE
        ),
        CatalogModel(
            "opencomic-ai-upscale-3x-compact-v2", "OpenComic AI Upscale Fast v2 3×", AiStep.UPSCALE, 3, ModelSource.OPENCOMIC,
            file("upscale/models/opencomic-ai-upscale-3x-compact-v2.param", 2975, "467b4698b5ce5530c58bb03bbc1e00b510fb9cbe650fe296e7ab33ac00caacd9"),
            file("upscale/models/opencomic-ai-upscale-3x-compact-v2.bin", 1223092, "626ed87f77ecf1e1639692265cf35ad5d7414419b221beb38fa6400c02075a51"),
            pageMs = 8_300,
            blurb = ModelBlurb.OPENCOMIC_UPSCALE
        ),
        CatalogModel(
            "opencomic-ai-upscale-4x-compact-v2", "OpenComic AI Upscale Fast v2 4×", AiStep.UPSCALE, 4, ModelSource.OPENCOMIC,
            file("upscale/models/opencomic-ai-upscale-4x-compact-v2.param", 2975, "d35d3bba1528d153f496a8433bfb55fcc383cde80cd0ac1dc2a6c9aea04a935e"),
            file("upscale/models/opencomic-ai-upscale-4x-compact-v2.bin", 1247368, "43c7baba3229c34e101e95e4d46c577a90ab4aba00287291f1e16f63edc57f23"),
            pageMs = 8_500,
            blurb = ModelBlurb.OPENCOMIC_UPSCALE
        ),
        CatalogModel(
            "artcnn-c4f32-ds", "ArtCNN C4F32 DS 2×", AiStep.UPSCALE, 2, ModelSource.ARTCNN,
            hosted("artcnn-c4f32-ds/artcnn-c4f32-ds.param", 1449, "9370c1573e7dc880d168f63c1b890338afd1f6364bcdcfb14cf941649320328f"),
            hosted("artcnn-c4f32-ds/artcnn-c4f32-ds.bin", 190940, "71dbfbd804f2d5f2703a961bcaf41fef311f6d79f619701058ffb8e6f81f9cc9"),
            pageMs = 2_000,
            blurb = ModelBlurb.ARTCNN
        ),
        CatalogModel(
            "cunny-8x32-ds-rp", "CuNNy 8x32 DS 2×", AiStep.UPSCALE, 2, ModelSource.CUNNY,
            hosted("cunny-8x32-ds-rp/cunny-8x32-ds-rp.param", 2710, "9c87e55c67fdcb88c9b1c0a5d5db2d5392c8a8b86569c885270dc77d471a9e2e"),
            hosted("cunny-8x32-ds-rp/cunny-8x32-ds-rp.bin", 301928, "a28337ff2ea4b8a6bd3458251a62f2f94391d9ac64087dd16d57a022b85cae4f"),
            pageMs = 2_900,
            blurb = ModelBlurb.CUNNY
        ),
        CatalogModel(
            "realesr-animevideov3-x2", "Real-ESRGAN AnimeVideo v3 2×", AiStep.UPSCALE, 2, ModelSource.REAL_ESRGAN,
            file("upscale/models/realesr-animevideov3-x2.param", 3173, "b88ff4f00ebf019a7fdac17fdd45a7fd3665d37509efc5baf2e4da2e24420a04"),
            file("upscale/models/realesr-animevideov3-x2.bin", 1247368, "548a36f9c3f4ab8da56cd3b13badf23968bee207b396dad14d04b830e5f2ab2d"),
            pageMs = 10_800,
            blurb = ModelBlurb.REAL_ESRGAN_ANIME
        ),
        CatalogModel(
            "realesr-animevideov3-x3", "Real-ESRGAN AnimeVideo v3 3×", AiStep.UPSCALE, 3, ModelSource.REAL_ESRGAN,
            file("upscale/models/realesr-animevideov3-x3.param", 3173, "d1a5755008791d09b57e3425fc9dd0bd26b00fdf79c606210bc0e693f8230881"),
            file("upscale/models/realesr-animevideov3-x3.bin", 1247368, "548a36f9c3f4ab8da56cd3b13badf23968bee207b396dad14d04b830e5f2ab2d"),
            pageMs = 11_100,
            blurb = ModelBlurb.REAL_ESRGAN_ANIME
        ),
        CatalogModel(
            "realesr-animevideov3-x4", "Real-ESRGAN AnimeVideo v3 4×", AiStep.UPSCALE, 4, ModelSource.REAL_ESRGAN,
            file("upscale/models/realesr-animevideov3-x4.param", 3077, "850a248e7c14c27e5bd8cf7265113a9441036a7db63963bb8aa5169d788a435e"),
            file("upscale/models/realesr-animevideov3-x4.bin", 1247368, "548a36f9c3f4ab8da56cd3b13badf23968bee207b396dad14d04b830e5f2ab2d"),
            pageMs = 14_000,
            blurb = ModelBlurb.REAL_ESRGAN_ANIME
        ),
        CatalogModel(
            "4x-lsdir-compact-c3", "LSDIR Compact C3 4×", AiStep.UPSCALE, 4, ModelSource.HELAMAN,
            file("upscale/models/4xLSDIRCompactC3.param", 2767, "6e3795fb5b6c68b2c39822e78bf83b575c918f96e9db6c36934a79e7d491d5e1"),
            file("upscale/models/4xLSDIRCompactC3.bin", 1247368, "692aa5e70c86522ce2d72b94f372751339a2fee65f515d06da5d496d8cf93667"),
            pageMs = 14_000,
            blurb = ModelBlurb.LSDIR
        ),
    )

    /** The repository the covers' text detector comes from, and the revision it is fetched at. */
    const val DETECTOR_REPOSITORY = "https://huggingface.co/qefro/pp-OCRv5-6-ncnn"
    const val DETECTOR_REVISION = "aa770c1e01b22010522bfaebe3f9bc4d239984fc"
    private const val DETECTOR_BASE = "$DETECTOR_REPOSITORY/resolve/$DETECTOR_REVISION/"

    /**
     * The covers' text detector ([TextDetector]): PP-OCRv5 mobile det (Apache-2.0), an ncnn
     * conversion with fp16 weights, as the repository (Apache-2.0) takes it from the
     * PaddleOCR-ncnn-CPP release archive (MIT), fetched unchanged. It has the layers, biases and
     * weights of the conversion by `tools/text-detector/convert.py`, but computes its
     * squeeze-and-excite steps as 1 x 1 convolutions plus two reshapes (there, inner products).
     */
    val TEXT_DETECTOR = ToolModel(
        "ppocrv5-mobile-det", "PP-OCRv5 mobile text detector",
        ModelFile("PP_OCRv5_mobile_det.param", DETECTOR_BASE + "PP_OCRv5_mobile_det.param", 23_816, "c08d6d1e9627ef6ca9d9acde8a51959fdc892e5f0419c662a63e4c22fbe47464"),
        ModelFile("PP_OCRv5_mobile_det.bin", DETECTOR_BASE + "PP_OCRv5_mobile_det.bin", 2_357_216, "1fc0f1151e5fa63328316333581869b69de226270d3cd8fce89fb1c260b3e7b5")
    )

    /** The app's own models. */
    val tools: List<ToolModel> = listOf(TEXT_DETECTOR)

    /** Every file set the store keeps ([ModelStore.prune]): the catalog's models, its shader chains and the app's own. */
    val sets: List<FileSet> get() = all + ShaderCatalog.all + tools

    /** The longest [CatalogModel.pageMs] the catalog takes, and the budget of a whole chain ([AiChain.slow]). */
    const val MAX_PAGE_MS = PageLimits.DEFAULT_TIMEOUT_MS * 2 / 3

    /** The [CatalogModel.pageMs] from which a model is slow ([AiModel.slow]): offered only with advanced settings shown. */
    const val SLOW_PAGE_MS = 10_000L

    /** The model Upscale runs until another is chosen: the fastest that works well. */
    const val DEFAULT_UPSCALE = "waifu2x-upconv7-2x"

    fun byId(id: String?): CatalogModel? = all.firstOrNull { it.id == id }

    /** [step]'s models, in catalog order. */
    fun forStep(step: AiStep): List<CatalogModel> = all.filter { it.step == step }

    /** [step]'s models a picker offers: with [keepSimple] on, the slow ones only when [current] (a catalog id). */
    fun offered(step: AiStep, keepSimple: Boolean, current: String?): List<CatalogModel> =
        forStep(step).filter { !keepSimple || !it.slow || it.id == current }
}
