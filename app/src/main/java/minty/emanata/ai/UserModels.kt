package minty.emanata.ai

import java.io.File
import java.util.Properties

/** A model a step can run: from the catalog ([CatalogModel]), a shader chain ([ShaderModel]), or imported by the user ([UserModel]). */
sealed interface AiModel {
    val id: String
    val name: String
    val step: AiStep

    /** Times larger out than in. */
    val scale: Int

    /**
     * Its time for a synthetic 1000 x 1500 page at its own scale: for catalog models
     * ([CatalogModel.pageMs]), started cool on the reference tablet's GPU in the tiles it picks
     * (some lighter sizes estimated until measured); for shader chains ([ShaderModel.pageMs]),
     * warm upper bounds until measured cool; for imported ones, measured once on this device as
     * it was taken in, at the heat the device had then ([UserModel.pageHeadroom]), so rougher.
     * [PageTimes] scales each basis apart.
     */
    val pageMs: Long

    /** Bytes of its files. */
    val bytes: Long

    /** What identifies its files' content, part of the key of every page it made ([AiChain.signature]). */
    val fingerprint: String

    /** True when a page takes it [ModelCatalog.SLOW_PAGE_MS] or longer. */
    val slow: Boolean get() = pageMs >= ModelCatalog.SLOW_PAGE_MS

    /** [pageMs] in whole seconds. */
    val pageSeconds: Long get() = (pageMs + 500) / 1000

    /** Its place among the sizes of one network ([ModelLevel]); null for a model that comes in one size. */
    val level: ModelLevel? get() = null
}

/**
 * A model's place in a family: the same network in sizes, lightest first ([names]), this one
 * at [index]. The family is [family] (an id shared by its levels), shown as [familyName].
 */
data class ModelLevel(val family: String, val familyName: String, val index: Int, val names: List<String>) {
    init {
        require(index in names.indices) { "Level $index of ${names.size}" }
    }

    /** The level's own name, as the family names its sizes. */
    val name: String get() = names[index]
}

/**
 * A model the user imported ([ModelImports]): ncnn files they converted elsewhere, named by
 * them, run at the [step] they chose, its [scale] and [pageMs] measured on this device when
 * it was taken in, the latter at thermal headroom [pageHeadroom] (NaN: unknown). No license
 * or source is known or claimed.
 */
data class UserModel(
    override val id: String,
    override val name: String,
    override val step: AiStep,
    override val scale: Int,
    override val pageMs: Long,
    val paramBytes: Long,
    val binBytes: Long,
    val paramSha256: String,
    val binSha256: String,
    val pageHeadroom: Float = Float.NaN
) : AiModel {
    override val bytes: Long get() = paramBytes + binBytes

    override val fingerprint: String get() = "${paramSha256.take(16)}:${binSha256.take(16)}"

    /** As kept beside its files. */
    internal fun toProperties() = Properties().apply {
        setProperty(NAME, name)
        setProperty(STEP, step.key)
        setProperty(SCALE, scale.toString())
        setProperty(PAGE_MS, pageMs.toString())
        setProperty(PARAM_SHA, paramSha256)
        setProperty(BIN_SHA, binSha256)
        if (!pageHeadroom.isNaN()) setProperty(PAGE_HEADROOM, pageHeadroom.toString())
    }

    companion object {
        private const val NAME = "name"
        private const val STEP = "step"
        private const val SCALE = "scale"
        private const val PAGE_MS = "pageMs"
        private const val PARAM_SHA = "paramSha256"
        private const val BIN_SHA = "binSha256"
        private const val PAGE_HEADROOM = "pageHeadroom"

        /** The longest name kept. */
        const val MAX_NAME = 60

        /** The model kept in [dir] as [p], its files [param] and [bin]; null when anything is missing or unreadable. */
        internal fun from(dir: File, p: Properties, param: File, bin: File): UserModel? {
            val name = p.getProperty(NAME)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val step = AiStep.entries.firstOrNull { it.key == p.getProperty(STEP) } ?: return null
            val scale = p.getProperty(SCALE)?.toIntOrNull()?.takeIf { it >= 1 } ?: return null
            val pageMs = p.getProperty(PAGE_MS)?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val paramSha = p.getProperty(PARAM_SHA) ?: return null
            val binSha = p.getProperty(BIN_SHA) ?: return null
            if (!param.isFile || !bin.isFile) return null
            val headroom = p.getProperty(PAGE_HEADROOM)?.toFloatOrNull() ?: Float.NaN
            return UserModel(dir.name, name, step, scale, pageMs, param.length(), bin.length(), paramSha, binSha, headroom)
        }
    }
}

/** The models the user imported, as last read from the store ([load]); any thread. */
object UserModels {
    @Volatile
    var all: List<UserModel> = emptyList()
        private set

    /** Reads the imported models from [store] again. Blocking (a few small files). */
    fun load(store: ModelStore) {
        all = store.userModels()
    }

    fun byId(id: String?): UserModel? = if (id == null) null else all.firstOrNull { it.id == id }
}

/** Every model a step can run: the catalog's, its shader chains ([ShaderCatalog]), then the user's imported ones ([UserModels]). */
object Models {
    fun all(): List<AiModel> = ModelCatalog.all + ShaderCatalog.all + UserModels.all

    fun byId(id: String?): AiModel? = ModelCatalog.byId(id) ?: ShaderCatalog.byId(id) ?: UserModels.byId(id)

    /** [step]'s models: the catalog's in order, its shader chains, then the imported ones. */
    fun forStep(step: AiStep): List<AiModel> = ModelCatalog.forStep(step) + ShaderCatalog.forStep(step) + UserModels.all.filter { it.step == step }

    /**
     * [step]'s models a picker offers: the catalog's ([ModelCatalog.offered]) and its shader
     * chains, every level, the slow ones last, then every imported one: the user took it in
     * themselves, slow or not.
     */
    fun offered(step: AiStep, keepSimple: Boolean, current: String?): List<AiModel> =
        (ModelCatalog.offered(step, keepSimple, current) + ShaderCatalog.forStep(step)).sortedBy { it.slow } +
            UserModels.all.filter { it.step == step }
}
