package minty.emanata.ai

/**
 * A model made of mpv hook shaders, run on the GPU ([ShaderSessions]): [files], run in order
 * as one chain, [scale] times larger out than in, at [step], from [source], at [level] of its
 * family ([ShaderCatalog.LEVELS]: the same network in sizes from lightest to heaviest).
 * [pageMs]: its time for a synthetic 1000 x 1500 page on the reference tablet's GPU, at its
 * own scale (for now warm, [ShaderCatalog]'s page times). [blurb]: what it does, as every
 * level of its family shares it.
 */
data class ShaderModel(
    override val id: String,
    override val step: AiStep,
    override val scale: Int,
    val source: ModelSource,
    override val files: List<ModelFile>,
    override val level: ModelLevel,
    override val pageMs: Long = 0,
    val blurb: ModelBlurb? = null
) : AiModel, FileSet {
    /** The family's name and the level's. */
    override val name: String get() = "${level.familyName}, ${level.name}"

    override val bytes: Long get() = files.sumOf { it.bytes }

    /** Its files' checksums and the runner's version ([ShaderSource.RUNNER]). */
    override val fingerprint: String get() = "r${ShaderSource.RUNNER}:" + files.joinToString(":") { it.sha256.take(16) }
}

/**
 * The shader chains the app offers: Anime4K v4.0.1 (MIT, bloc97), its files fetched unchanged
 * from the upstream repository at the release's commit, so their checksums hold. Five families,
 * each in every size from S to UL: the upscaler alone and with denoise, and mpv's modes A
 * (highlight clamp, restore, upscale), B (the same with the soft restore) and C (highlight
 * clamp, upscale with denoise), the families [ModelRatings] rates. Pickers offer every level.
 */
object ShaderCatalog {
    /** The repository the files come from, and the commit they are fetched at (tag v4.0.1). */
    const val REPOSITORY = "https://github.com/bloc97/Anime4K"
    const val COMMIT = "4029bf701ecaa15f163cdc49cffe5501c1acf410"
    const val BASE = "https://raw.githubusercontent.com/bloc97/Anime4K/$COMMIT/glsl/"

    /** Anime4K's sizes, lightest first. */
    val LEVELS = listOf("S", "M", "L", "VL", "UL")

    private fun file(path: String, bytes: Long, sha256: String) = ModelFile(path.substringAfterLast('/'), BASE + path, bytes, sha256)

    private val CLAMP = file("Restore/Anime4K_Clamp_Highlights.glsl", 2_795, "a2a9bf7fbc1d75d09660ca2e701e4d7fb0cf5457b94da47e1825032fa2b3671a")

    private val RESTORE = listOf(
        file("Restore/Anime4K_Restore_CNN_S.glsl", 17_136, "97c24dc370ab300c108bfaa09db7f175aeff343674842c299cf3940a3d330427"),
        file("Restore/Anime4K_Restore_CNN_M.glsl", 35_916, "67ea3ed26539e8de3b7d307688535d2ff17e8d147e11dda0247da7770dbecf41"),
        file("Restore/Anime4K_Restore_CNN_L.glsl", 69_921, "d6efe215e6ee8af1ec560478a91afc1df83fac4ba43b2c806ee61ca2267ed674"),
        file("Restore/Anime4K_Restore_CNN_VL.glsl", 144_075, "35036722733305cd4d4e57660b883bbe2569ba2914033c254327107d7b77e35e"),
        file("Restore/Anime4K_Restore_CNN_UL.glsl", 308_660, "81ec48ff700108c8e1571ed82d4527006ee200c05e94f8b7f836c02179169b40")
    )

    private val RESTORE_SOFT = listOf(
        file("Restore/Anime4K_Restore_CNN_Soft_S.glsl", 17_198, "9f6867f2ef42786729522d86fe24147cb4ea145418e3974df70496acf52dc392"),
        file("Restore/Anime4K_Restore_CNN_Soft_M.glsl", 36_016, "a78a2c76898e08e09e442a9628c64208c26e8e15789649b8755223f009794c02"),
        file("Restore/Anime4K_Restore_CNN_Soft_L.glsl", 70_020, "8f2a5c73b526c6e4c67bce0366f7dcd7410bfa4a31e718240d86f53660788e63"),
        file("Restore/Anime4K_Restore_CNN_Soft_VL.glsl", 144_204, "094334b0e20c1a201fe4941c7c68de72451e5aee9efb5524d7fb82b12dca64b9"),
        file("Restore/Anime4K_Restore_CNN_Soft_UL.glsl", 308_873, "41d71cad7b2f3af9086852fca70046f763272d2cb49dd83de9647d942866cac8")
    )

    private val UPSCALE = listOf(
        file("Upscale/Anime4K_Upscale_CNN_x2_S.glsl", 18_638, "4c53ec2e287908f7ee7bcb266b0170421626d663576468b7d7dafc62962649a4"),
        file("Upscale/Anime4K_Upscale_CNN_x2_M.glsl", 37_685, "716e02098a68f0d648761f2b96b4dd139e1cb09b174bb369fca3aa34328fff7e"),
        file("Upscale/Anime4K_Upscale_CNN_x2_L.glsl", 73_443, "db1fedf7be82f6fd9034e6bf39b64daf2b7576988bb584ec38f24f5236b1cd97"),
        file("Upscale/Anime4K_Upscale_CNN_x2_VL.glsl", 146_743, "5638fe31c37c151a3443fea3451a3ef91af073f4dbb9615f6c0d1e29db11493d"),
        file("Upscale/Anime4K_Upscale_CNN_x2_UL.glsl", 290_257, "fa7cf0ecc1cca84d8291bbff5a42b60f5816d57b4e97d42bc377235ac8db02e8")
    )

    private val UPSCALE_DENOISE = listOf(
        file("Upscale+Denoise/Anime4K_Upscale_Denoise_CNN_x2_S.glsl", 18_667, "1a45ad3aa20d8368399f2fd46791deed957c2fd0a4afd131cc92516827abab93"),
        file("Upscale+Denoise/Anime4K_Upscale_Denoise_CNN_x2_M.glsl", 37_714, "8c72b042e2301fe66a45c3089720459148e2504cd72af16f9c0d5017ff14181e"),
        file("Upscale+Denoise/Anime4K_Upscale_Denoise_CNN_x2_L.glsl", 73_584, "6cc4604c9544fd4fd9e3a75fd797511bf5fe1e626e9e1dbcee03302823a63207"),
        file("Upscale+Denoise/Anime4K_Upscale_Denoise_CNN_x2_VL.glsl", 146_811, "359c48fe5a317fbc6b706ce368401eef496e84ed98abac7a43efebca2b65d79b"),
        file("Upscale+Denoise/Anime4K_Upscale_Denoise_CNN_x2_UL.glsl", 290_040, "38bd11e7e92ff1a615a274be912d6f2cfe67f5b43771f0383a91c6ab3d0f8cb6")
    )

    /** A family: its id (model ids are it and the level), name, what it does, and files at each level. */
    private class Family(val id: String, val name: String, val blurb: ModelBlurb, val files: (Int) -> List<ModelFile>, val pageMs: LongArray)

    /**
     * How many times its cool time a chain's page time here is ([PageTimes] corrects by it when
     * scaling one engine by the other's): the times are a synthetic 1000 x 1500 page in
     * 512-pixel tiles on the reference tablet's GPU (Adreno 730), from one batch of all 25
     * chains run back to back, so warm rather than started cool as [AiModel.pageMs] asks (A UL
     * about 1.7 times its cool time); upper bounds until a cool session measures them again,
     * when this becomes 1.
     */
    const val WARM = 1.7

    /** Page times, as [WARM] describes them. */
    private val FAMILIES = listOf(
        Family("anime4k-a", "Anime4K A (sharp) 2×", ModelBlurb.ANIME4K_A, { l -> listOf(CLAMP, RESTORE[l], UPSCALE[l]) }, longArrayOf(180, 300, 470, 590, 840)),
        Family("anime4k-b", "Anime4K B (clean) 2×", ModelBlurb.ANIME4K_B, { l -> listOf(CLAMP, RESTORE_SOFT[l], UPSCALE[l]) }, longArrayOf(250, 340, 490, 760, 830)),
        Family("anime4k-c", "Anime4K C (balanced) 2×", ModelBlurb.ANIME4K_C, { l -> listOf(CLAMP, UPSCALE_DENOISE[l]) }, longArrayOf(170, 210, 370, 530, 730)),
        Family("anime4k-upscale", "Anime4K Upscale 2×", ModelBlurb.ANIME4K_UPSCALE, { l -> listOf(UPSCALE[l]) }, longArrayOf(140, 230, 330, 500, 730)),
        Family("anime4k-upscale-denoise", "Anime4K Upscale + Denoise 2×", ModelBlurb.ANIME4K_UPSCALE_DENOISE, { l -> listOf(UPSCALE_DENOISE[l]) }, longArrayOf(150, 210, 270, 490, 730)),
    )

    /** Every chain, family by family, each from its lightest level to its heaviest. */
    val all: List<ShaderModel> = FAMILIES.flatMap { f ->
        LEVELS.indices.map { l ->
            ShaderModel(
                "${f.id}-${LEVELS[l].lowercase()}", AiStep.UPSCALE, 2, ModelSource.ANIME4K, f.files(l),
                ModelLevel(f.id, f.name, l, LEVELS), pageMs = f.pageMs[l], blurb = f.blurb
            )
        }
    }

    fun byId(id: String?): ShaderModel? = all.firstOrNull { it.id == id }

    /** [step]'s chains, in catalog order. */
    fun forStep(step: AiStep): List<ShaderModel> = all.filter { it.step == step }

    /** The levels of [family], lightest first. */
    fun family(family: String): List<ShaderModel> = all.filter { it.level.family == family }
}
