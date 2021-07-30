package ski.chrzanow.foldableprojectview.projectView

import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.TreeStructureProvider
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.nodes.PsiDirectoryNode
import com.intellij.ide.projectView.impl.nodes.PsiFileNode
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessModuleDir
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.ignore.cache.PatternCache
import com.intellij.openapi.vcs.changes.ignore.lang.Syntax
import ski.chrzanow.foldableprojectview.settings.FoldableProjectSettings
import ski.chrzanow.foldableprojectview.settings.FoldableProjectSettingsListener
import ski.chrzanow.foldableprojectview.settings.FoldableProjectState
import java.util.Locale

class FoldableTreeStructureProvider(project: Project) : TreeStructureProvider {

    private val settings = project.service<FoldableProjectSettings>()
    private val patternCache = PatternCache.getInstance(project)
    private var previewState: FoldableProjectState? = null
    private val state get() = previewState ?: settings

    init {
        val view = ProjectView.getInstance(project)

        project.messageBus
            .connect(project)
            .subscribe(FoldableProjectSettingsListener.TOPIC, object : FoldableProjectSettingsListener {
                override fun settingsChanged(settings: FoldableProjectSettings) {
                    view.currentProjectViewPane?.updateFromRoot(true)
                }
            })
    }

    override fun modify(
        parent: AbstractTreeNode<*>,
        children: MutableCollection<AbstractTreeNode<*>>,
        viewSettings: ViewSettings?,
    ): Collection<AbstractTreeNode<*>> {
        val project = parent.project ?: return children

        return when {
            !state.foldingEnabled -> children
            parent !is PsiDirectoryNode -> children
            !isModule(parent, project) -> children
            else -> children.match().let { matched ->
                when {
                    state.hideAllGroups -> children - matched
                    state.hideEmptyGroups && matched.isEmpty() -> children
                    else -> children - matched + FoldableProjectViewNode(project, viewSettings, matched)
                }
            }
        }
    }

    private fun isModule(node: PsiDirectoryNode, project: Project) = node.virtualFile?.let {
        ModuleUtil.findModuleForFile(it, project)?.guessModuleDir() == it
    } ?: false

    private fun MutableCollection<AbstractTreeNode<*>>.match() = this
        .filter { it.isFileOrDirectory() }
        .filter {
            when {
                it.matchesPattern() -> true
                state.foldIgnoredFiles -> when {
                    it.isIgnored() -> true
                    it.hasNoChildren() -> true
                    else -> false
                }
                else -> false
            }
        }

    private fun AbstractTreeNode<*>.isFileOrDirectory() = run {
        when (this) {
            is PsiDirectoryNode -> state.foldDirectories
            is PsiFileNode -> true
            else -> false
        }
    }

    private fun AbstractTreeNode<*>.isIgnored() = this.fileStatus.equals(FileStatus.IGNORED)

    /**
     * Match directories without a file, or containing only ignored files
     */
    private fun AbstractTreeNode<*>.hasNoChildren(): Boolean = run {
        if (this is PsiFileNode) return this.isIgnored()
        if (this.children.isEmpty()) return true
        this.children.stream().allMatch {
            when (it) {
                is PsiFileNode -> it.isIgnored()
                is PsiDirectoryNode -> it.hasNoChildren()
                else -> true
            }
        }
    }

    private fun AbstractTreeNode<*>.matchesPattern() = run {
        when (this) {
            is ProjectViewNode -> this.virtualFile?.name ?: this.name
            else -> this.name
        }.caseInsensitive().let { name ->
            state.patterns
                    .caseInsensitive()
                    .split(' ')
                    .any { pattern ->
                        patternCache?.createPattern(pattern, Syntax.GLOB)?.matcher(name)?.matches() ?: false
                    }
        }
    }

    private fun String?.caseInsensitive() = when {
        this == null -> ""
        state.caseInsensitive -> lowercase(Locale.getDefault())
        else -> this
    }

    fun withState(state: FoldableProjectState) {
        previewState = state
    }
}
