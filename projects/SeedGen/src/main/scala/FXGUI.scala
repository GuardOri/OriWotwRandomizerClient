import java.nio.file.{Path, Paths}
import org.json4s._
import org.json4s.native.Serialization
import scalafx.Includes._
import scalafx.application.JFXApp
import scalafx.application.JFXApp.PrimaryStage
import scalafx.scene.Scene
import javafx.scene.layout.{Border => JBorder}
import scalafx.scene.layout.BorderPane
import scalafx.scene.paint.Color._
import scalafx.scene.text.Text
import scalafx.stage.DirectoryChooser
import SeedGenerator.implicits._
import scalafx.application.Platform
import scalafx.beans.binding.Bindings
import scalafx.beans.property.StringProperty
import scalafx.geometry.{Insets, Pos}
import scalafx.scene.control.Alert.AlertType
import scalafx.scene.control._
import scalafx.scene.layout.{BorderStroke, BorderStrokeStyle, BorderWidths, CornerRadii, GridPane, VBox}
import scalafx.scene.text.{Font, TextAlignment}
import java.util.prefs.Preferences

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.sys.process._
package SeedGenerator {


  import scalafx.beans.binding.{BooleanBinding, ObjectBinding}
  import scalafx.beans.property.{BooleanProperty, ObjectProperty}
  import scalafx.stage.FileChooser
  import scalafx.util.StringConverter

  import scala.util.{Failure, Success, Try}
  object FXGUI extends JFXApp {
    implicit class ObjectPropExts[T](wrapped: ObjectProperty[T]) {
      def mapObjBind[V](f: T => V): ObjectBinding[V] = Bindings.createObjectBinding(() => f(wrapped()), wrapped)
      def mapBoolBind(f: T => Boolean): BooleanBinding = Bindings.createBooleanBinding(() => f(wrapped()), wrapped)
      def mapBoolProp(outbound: T => Boolean, inbound: (T, Boolean) => T): BooleanProperty = {
        val prop = new BooleanProperty()
        prop.setValue(outbound(wrapped()))
        wrapped.onChange((_, _, nval) => prop.setValue(outbound(nval)))
        prop.onChange((_, _, nval) => wrapped.setValue(inbound(wrapped(), nval)))
        prop
      }
      def mapStringProp(outbound: T => String, inbound: (T,String) => T): StringProperty = {
        val prop = new StringProperty()
        prop.setValue(outbound(wrapped()))
        wrapped.onChange((_, _, nval) => prop.setValue(outbound(nval)))
        prop.onChange((_, _, nval) => wrapped.setValue(inbound(wrapped(), nval)))
        prop
      }
    }


    val APP_NAME: String = "RandoSeedGen"
    val pref: Preferences = Preferences.userRoot.node(APP_NAME)
    case class DoublePref(key: String) {
      def set(v: Double): Unit = pref.putDouble(key, v)
      def get: Option[Double] = {
        val ret = pref.getDouble(key, Double.NaN)
        !ret.isNaN ? ret
      }
      def ??(orElse: Double): Double = get ?? orElse
    }
    val WIN_X: DoublePref = DoublePref("WINPOS_X")
    val WIN_Y: DoublePref = DoublePref("WINPOS_Y")
    val WIN_W: DoublePref = DoublePref("WIN_WIDTH")
    val WIN_H: DoublePref = DoublePref("WIN_HEIGHT")

    val settingsFile: Path = "SeedGenSettings.json".jarf
    var currentOp: Option[Future[Unit]] = None
    val headerFilePath: Path =  ".seedHeader".jarf
    val settings: ObjectProperty[Settings] = new ObjectProperty(null, "settings", settingsFromFile)
    val outputDirectory: StringProperty = settings.mapStringProp(_.outputFolder, (set, path) => set.copy(outputFolder = path))
    val header = new ObjectProperty[Seq[String]](null, "header_text", headerFilePath.readLines ?? "// Replace this text with a seed header, if desired")
    val lastSeedText: StringProperty = new StringProperty(null, "last_seed")
    val seedName: StringProperty = new StringProperty(null, "seed_name", "")
    val logArea: TextArea = new TextArea { editable = false; font = Font("Monospaced").delegate}
    var lastSeed: Option[String] = None
    val lastSeedName: StringProperty = new StringProperty(null, "last_seed_name", "N/A")

    Settings.provider = FXSettingsProvider
    Logger.current  = FXLogger
    def settingsFromFile: Settings = {
      implicit val formats: Formats = Serialization.formats(NoTypeHints)
      settingsFile.read.map(Serialization.read[Settings]).getOrElse(Settings())
    }
    def outputPath: String = {
      val name_base = outputDirectory().f.toAbsolutePath.resolve((seedName() == "") ? "seed" ?? seedName()).toString
      var ret = s"$name_base.wotwr"
      var i = 0
      while(ret.f.exists) {
        ret = s"${name_base}_$i.wotwr"
        i += 1
      }
      lastSeed = Some(ret)
      ret
    }

    def settingsToggle(name: String, tooltipText: String, selectedBinding: BooleanProperty): ToggleButton =  new ToggleButton(name){
      selected = selectedBinding()
      selected <==> selectedBinding
      tooltip = tooltipText
      border <== when (selected) choose
        new JBorder(new BorderStroke(stroke = SkyBlue, BorderStrokeStyle.Solid, new CornerRadii(4f), BorderWidths.Default))  otherwise
        new JBorder(new BorderStroke(stroke = Black, BorderStrokeStyle.None, new CornerRadii(3f), BorderWidths.Default))
    }
    val outputDirChooser: DirectoryChooser = new DirectoryChooser { initialDirectory = outputDirectory().f.toFile }
    val importSettingsChooser: FileChooser = new FileChooser {
      initialDirectory <== settings.mapObjBind(_.outputFolder.f.toFile)
      selectedExtensionFilter = new FileChooser.ExtensionFilter("Wotw Rando Seed File", Seq(".wotwr"))
    }

    val runLastSeedButton: Button = new Button("Run Last Seed") {disable = true}
    val changeFolderButton: Button = new Button("Change Folder") {
      onAction = _ => {
        Option(outputDirChooser.showDialog(stage)).map(f => {
          outputDirectory.setValue(f.getAbsolutePath)
          settingsFile.write(Settings.toJson)
        })
      }
    }
    val importSettingsButton: Button = new Button("Import Settings") {
      onAction = _ => {
        Option(importSettingsChooser.showOpenDialog(stage)).map(f =>
          settings.setValue(ReachChecker.settingsFromSeed(f.toPath, updateSpawn = false).copy(outputFolder = outputDirectory(), debugInfo = settings().debugInfo))
        )
      }
    }


    val raceModeButton:         ToggleButton = settingsToggle("Race Mode",          "Generate a spoiler-free version of the seed for racing", settings.mapBoolProp(!_.spoilers, (s, b) => s.copy(spoilers = !b)))
    val debugButton:            ToggleButton = settingsToggle("Debug Mode",         "Outputs spoiler-containing generation info to the console, and enables the last seed tab", settings.mapBoolProp(_.debugInfo, (s, b) => s.copy(debugInfo = b)))
    val questsButton:           ToggleButton = settingsToggle("Items on Quests",    "Receive items from quest progress and completion", settings.mapBoolProp(_.questLocs, (s, b) => s.copy(questLocs = b)))
    val bonusItemsButton:       ToggleButton = settingsToggle("Bonus Items",        "Enables rando-only bonus pickups, including weapon upgrades", settings.mapBoolProp(_.bonusItems, (s, b) => s.copy(bonusItems = b)))
    val teleportersButton:      ToggleButton = settingsToggle("Teleporters",        "Add items to the item pool that unlock teleporters", settings.mapBoolProp(_.tps, (s, b) => s.copy(tps = b)))
    val gorlekPathsButton:      ToggleButton = settingsToggle("Gorlek paths",       "Enable Gorlek-difficulty paths", settings.mapBoolProp(_.gorlekPaths, (s, b) => s.copy(gorlekPaths = b)))
    val uncheckedPathsButton:   ToggleButton = settingsToggle("Unsafe paths",       "Enable paths that have not yet been sorted into a difficulty group.", settings.mapBoolProp(_.unsafePaths, (s, b) => s.copy(unsafePaths = b)))
    val glitchPathsButton:      ToggleButton = settingsToggle("Glitched paths",     "Enable paths that rely on glitches, such as Sentry Jumps", settings.mapBoolProp(_.glitchPaths, (s, b) => s.copy(glitchPaths = b)))
    val seirLaunchButton:       ToggleButton = settingsToggle("Launch on Seir",     "Places launch on Seir", settings.mapBoolProp(_.seirLaunch, (s, b) => s.copy(seirLaunch = b)))
    val randomSpawnButton:      ToggleButton = settingsToggle("Random spawn",       "Spawn at a randomly-chosen spirit well", settings.mapBoolProp(_.flags.randomSpawn, (s, b) => s.copy(flags = s.flags.copy(randomSpawn = b))))
    val rainButton:             ToggleButton = settingsToggle("Rainy Marsh",        "Start the game in the 'prologue' marsh state, with rain present and Howl enabled", settings.mapBoolProp(_.flags.rain, (s, b) => s.copy(flags = s.flags.copy(rain = b))))
    val noKSDoorsButton:        ToggleButton = settingsToggle("Remove KS doors",    "Start the game with every keystone door opened", settings.mapBoolProp(_.flags.noKSDoors, (s, b) => s.copy(flags = s.flags.copy(noKSDoors = b))))
    val forceWispsButton:       ToggleButton = settingsToggle("Force Wisps",        "Adds requirement: Collect every Wisp", settings.mapBoolProp(_.flags.forceWisps, (s, b) => s.copy(flags = s.flags.copy(forceWisps = b))))
    val worldTourButton:        ToggleButton = settingsToggle("World Tour",         "Adds requirement: Collect a Relic from every zone with one", settings.mapBoolProp(_.flags.worldTour, (s, b) => s.copy(flags = s.flags.copy(worldTour = b))))
    val forceQuestsButton:      ToggleButton = settingsToggle("Force Quests",       "Adds requirement: Complete every Quest", settings.mapBoolProp(_.flags.forceQuests, (s, b) => s.copy(flags = s.flags.copy(forceQuests = b))))
    val forceTreesButton:       ToggleButton = settingsToggle("Force Trees",        "Adds requirement: Collect all Ancestral Trees", settings.mapBoolProp(_.flags.forceTrees, (s, b) => s.copy(flags = s.flags.copy(forceTrees = b))))
    val zoneHintsButton:        ToggleButton = settingsToggle("Zone Hints",         "Lupo sells the hints", settings.mapBoolProp(!_.flags.noHints, (s, b) => s.copy(flags = s.flags.copy(noHints = !b))))
    val swordSpawnButton:       ToggleButton = settingsToggle("Spawn with Sword",   "Start the game with Spirit Edge in your inventory and equipped", settings.mapBoolProp(!_.flags.noSword, (s, b) => s.copy(flags = s.flags.copy(noSword = !b))))
    val webConnButton:          ToggleButton = settingsToggle("Enable Netcode",     "Connect to the webserver (for bingo or co-op)", settings.mapBoolProp(_.webConn, (s, b) => s.copy(webConn = b)))
    val seedNameInput:          TextField    = new TextField { text <==> seedName; prefColumnCount = 10 }
    val outputLabel:            Label        = new Label { text <== outputDirectory }

    runLastSeedButton.onAction = _ => lastSeed match {
      case Some(file) => Seq("cmd", "/C", file.f.toString).run
      case None =>
        Logger.warn("Last seed file not found!")
        runLastSeedButton.disable = true
    }


    val headerTextArea: TextArea = new TextArea() {
      text.bindBidirectional[Seq[String]](header, StringConverter[Seq[String]](
        body => body.split("\n"),
        lines => lines.mkString("\n")
      ))
        font = Font("Monospaced")
    }
    header.addListener((_, _, newV: Seq[String]) => {
      headerFilePath.write(newV.mkString(scala.util.Properties.lineSeparator))
    })

    stage = new PrimaryStage {
      title = "Wotw Rando Seed Generator"
      width = WIN_W ?? 800
      height = WIN_H ?? 600
      WIN_X.get.foreach(pref_c => x = pref_c)
      WIN_Y.get.foreach(pref_c => y = pref_c)

      onCloseRequest = _ => {
        settingsFile.write(Settings.toJson)
        WIN_W.set(width())
        WIN_H.set(height())
        WIN_X.set(x())
        WIN_Y.set(y())
      }

      val mainTabPane: TabPane = getTabPane
      val lastSeedTab: Tab = getSeedTab

      scene = new Scene {
        fill = LightGray
        content = mainTabPane
      }
      headerTextArea.prefWidth <== mainTabPane.prefWidth - 40
      mainTabPane.prefWidth <== scene.value.widthProperty()
      mainTabPane.prefHeight <== scene().heightProperty()

      private def getTabPane = {
        new TabPane {
          border = new JBorder(new BorderStroke(stroke = Black, BorderStrokeStyle.Solid, new CornerRadii(3f), BorderWidths.Default))
          padding = Insets(15)
          tabs = Seq(
            getMainTab,
            getHeaderTab
          )
        }
      }

      private def getOptions: GridPane = {
        val gp = new GridPane()
        val clearBtn = new Button("Clear Log") {
          onAction = _ => logArea.setText("")
        }

        gp.addRow(0, new Label("Logic Groups: "), gorlekPathsButton, glitchPathsButton, uncheckedPathsButton)
        gp.addRow(1, new Label("Goal Modes: "), forceTreesButton, forceWispsButton, forceQuestsButton, worldTourButton)
        gp.addRow(2, new Label("Spawn Opts: "), swordSpawnButton, randomSpawnButton, rainButton, noKSDoorsButton)
        gp.addRow(3, new Label("Misc Opts: "),  seirLaunchButton,zoneHintsButton, questsButton)
        gp.addRow(4, new Label(""), bonusItemsButton, teleportersButton, raceModeButton, webConnButton)
        gp.addRow(5,  new Label(s"Output folder: "), outputLabel, changeFolderButton, importSettingsButton)
        gp.addRow(6, getGenerateButton, new Label(s"Seed Name (Optional):"), seedNameInput, runLastSeedButton, debugButton, clearBtn)
        gp
      }

      private def getGenerateButton = {
        val generateButton = new Button("Generate") {
          tooltip = "build a seed with the specified parameters"
        }
        generateButton.onAction = _ => {
          settingsFile.write(Settings.toJson)
          generateButton.disable = true
          currentOp = Some(Future {
            val succ = Try {
              Logger.info("Building...")
              if (seedName() != "") {
                Logger.info(s"Seeded RNG with ${seedName()}")
            SeedGenerator.Runner.setSeed(seedName().hashCode)
            }
            if (SeedGenerator.Runner(outputPath)) {
              Logger.info(s"Finished generating seed!")
              true
            } else {
              lastSeed = None
              Logger.error(s"Failed to generate seed :c")
              false
            }
          } match {
              case Failure(e) => Logger.error(e); false
              case Success(_) => true
            }
            Platform.runLater(() => {
              runLastSeedButton.disable = !succ
              generateButton.disable = false
              lastSeed.flatMap(_.f.read) foreach (v => {
                lastSeedText.setValue(v)
                lastSeedName.setValue(lastSeed.get.f.getFileName.toString)
                if (debugButton.selected()) {
                  if (!mainTabPane.getTabs.contains(lastSeedTab))
                    mainTabPane.getTabs.append(lastSeedTab)
                } else if (mainTabPane.getTabs.contains(lastSeedTab))
                  mainTabPane.getTabs.remove(lastSeedTab)
              })
              currentOp = None
            })
          })
        }
        generateButton
      }

      private def getSeedTab = new Tab() {
        closable = false
        id = "seed-view"
        text = "seed"
        tooltip = "view your latest seed here"
        content = new VBox() {
          children = Seq(
            new Text() {
              text <== Bindings.createStringBinding(() => s"last rolled seed: ${lastSeedName()}", lastSeedName)
              alignmentInParent = Pos.TopCenter
              textAlignment = TextAlignment.Center
            },
            new TextArea() {
              text <==> lastSeedText
              font = Font("Monospaced")
              editable = false
              wrapText = true
              alignmentInParent = Pos.Center
              prefHeight <== mainTabPane.prefHeight - 40
              prefWidth <== mainTabPane.prefWidth - 10
            }
          )
        }
      }

      private def getMainTab = new Tab {
        closable = false
        id = "main"
        text = "main"
        tooltip = "Main seedgen tab"
        content = new BorderPane {
          top = getOptions
          center = logArea
        }
      }

      private def getHeaderTab = new Tab {
        closable = false
        id = "header"
        text = "header"
        tooltip = "for customizing seeds"
        content = new VBox() {
          children = Seq(
            new Text("Any text added below will be placed at the top of your seed file.\nYou can use this to preplace items") {
              alignmentInParent = Pos.Center
            },
            headerTextArea
          )
        }
      }
    }
    object FXSettingsProvider extends SettingsProvider {
      def get: Settings = settings.getValue
      override def userHeader: Seq[String] = FXGUI.header()
    }
    object FXLogger extends Logger {
      override def enabled: Seq[LogLevel] = Seq(INFO, WARN, ERROR) ++ Settings.debugInfo ? DEBUG
      override def write(x: =>Any): Unit = {
        //        logArea.setScrollTop(logArea.height())
        Platform.runLater(() => logArea.appendText(s"$x\n"))
      }
    }

    def versionCheck(): Unit = {
      val javaVersion = System.getProperty("java.version")
      if(javaVersion.takeWhile(_ != '.').toIntOption ?? 0 < 11)
      new Alert(AlertType.Warning, s"Seedgen not compatible with Java version $javaVersion.\nPlease install version 11 or higher.\nYou may need to uninstall older versions.").showAndWait()
    }

    def show(): Unit = {
      main(Array.empty[String])
    }
  }

}