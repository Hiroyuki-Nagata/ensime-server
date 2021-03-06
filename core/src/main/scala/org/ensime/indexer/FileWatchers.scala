package org.ensime.indexer

import java.io.File

import akka.event.slf4j.SLF4JLogging
import org.apache.commons.vfs2._
import org.apache.commons.vfs2.impl._

import org.ensime.api._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

trait ClassfileListener {
  def classfileAdded(f: FileObject): Unit
  def classfileRemoved(f: FileObject): Unit
  def classfileChanged(f: FileObject): Unit
}

trait SourceListener {
  def sourceAdded(f: FileObject): Unit
  def sourceRemoved(f: FileObject): Unit
  def sourceChanged(f: FileObject): Unit
}

/**
 * Watches the user's target output directories for classfiles that
 * need to be indexed or updated (i.e. picks up changes when the
 * compiler produces any output).
 *
 * If we were Java 7+ we'd be using
 * http://docs.oracle.com/javase/7/docs/api/java/nio/file/WatchService.html
 */
class ClassfileWatcher(
    config: EnsimeConfig,
    listeners: Seq[ClassfileListener]
)(
    implicit
    vfs: EnsimeVFS
) extends SLF4JLogging {

  private val fm = new DefaultFileMonitor(new FileListener {
    def watched(event: FileChangeEvent) = {
      val name = event.getFile.getName
      EnsimeVFS.ClassfileSelector.include(name.getExtension)
    }

    def fileChanged(event: FileChangeEvent): Unit =
      if (watched(event))
        listeners foreach { list => Future { list.classfileChanged(event.getFile) } }
    def fileCreated(event: FileChangeEvent): Unit =
      if (watched(event))
        listeners foreach { list => Future { list.classfileAdded(event.getFile) } }
    def fileDeleted(event: FileChangeEvent): Unit =
      if (watched(event))
        listeners foreach { list => Future { list.classfileRemoved(event.getFile) } }
  })
  fm.setRecursive(true)
  fm.start()

  // WORKAROUND https://issues.apache.org/jira/browse/VFS-536
  // We don't have a dedicated test for this because it is an upstream bug
  private val workaround = new DefaultFileMonitor(
    new FileListener {
      private def targets: Set[FileName] = for {
        dir <- config.targetClasspath
        ref = vfs.vfile(dir)
      } yield ref.getName()

      def watched(event: FileChangeEvent) = {
        val dir = event.getFile
        val changed = dir.getName
        // read backwards as: "changed is an ancestor of target"
        targets.exists { target => target isAncestor changed }
      }
      def fileChanged(event: FileChangeEvent): Unit = {
        if (watched(event)) {
          // a fast delete followed by a create looks like a change
          log.debug(s"${event.getFile} was possibly recreated")
          reset()
        }
      }
      def fileCreated(event: FileChangeEvent): Unit = {
        if (watched(event)) {
          log.debug(s"${event.getFile} was created")
          reset()
        }
      }
      def fileDeleted(event: FileChangeEvent): Unit = {
        if (watched(event)) {
          log.debug(s"${event.getFile} was deleted")
          // nothing to do, we need to wait for it to return
        }
      }
    }
  )

  workaround.setRecursive(false)
  workaround.start()

  private def ancestors(f: FileObject): List[FileObject] = {
    val parent = f.getParent
    if (parent == null) Nil
    else parent :: ancestors(parent)
  }

  // If directories are recreated, triggering the VFS-536 bug, we end up
  // calling reset() a lot of times. We should probably debounce it, but
  // it seems to happen so quickly that no damage is done.
  private def reset(): Unit = {
    // When this triggers we tend to see it multiple times because
    // we're watching the various depths within the project.
    log.info("Setting up new file watchers")

    val root = vfs.vfile(config.root)

    // must remove then add to avoid leaks
    for {
      d <- config.targetClasspath
      dir = vfs.vfile(d)
      _ = fm.removeFile(dir)
      _ = fm.addFile(dir)
      ancestor <- ancestors(dir)
      if ancestor.getName isAncestor root.getName
      _ = workaround.removeFile(ancestor)
      _ = workaround.addFile(ancestor)
    } {
      // side effects in the for comprehension
    }

    workaround.removeFile(root)
    workaround.addFile(root)
  }

  reset()

  def shutdown(): Unit = {
    fm.stop()
    workaround.stop()
  }

}

class SourceWatcher(
    config: EnsimeConfig,
    listeners: Seq[SourceListener]
)(
    implicit
    vfs: EnsimeVFS
) extends SLF4JLogging {
  private val fm = new DefaultFileMonitor(new FileListener {
    def watched(event: FileChangeEvent) =
      EnsimeVFS.SourceSelector.include(event.getFile.getName.getExtension)

    def fileChanged(event: FileChangeEvent): Unit =
      if (watched(event))
        listeners foreach (_.sourceChanged(event.getFile))
    def fileCreated(event: FileChangeEvent): Unit =
      if (watched(event))
        listeners foreach (_.sourceAdded(event.getFile))
    def fileDeleted(event: FileChangeEvent): Unit =
      if (watched(event))
        listeners foreach (_.sourceRemoved(event.getFile))
  })
  fm.setRecursive(true)
  fm.start()

  config.modules.values.foreach { m =>
    m.sourceRoots foreach { r => fm.addFile(vfs.vfile(r)) }
  }

  def shutdown(): Unit = {
    fm.stop()
  }
}
