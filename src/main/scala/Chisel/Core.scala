package Chisel
import scala.util.DynamicVariable
import scala.collection.immutable.ListMap
import scala.collection.mutable.{ArrayBuffer, Stack, HashSet, HashMap, LinkedHashMap}
import java.lang.reflect.Modifier._
import java.lang.Double.longBitsToDouble
import java.lang.Float.intBitsToFloat

class IdGen {
  private var counter = -1L
  def next: Long = {
    counter += 1
    counter
  }
}

private class ChiselRefMap {
  private val _refmap = new HashMap[Long,Immediate]()

  def overrideRefForId(id: Id, name: String): Unit =
    _refmap(id._id) = Ref(name)

  def setRefForId(id: Id, name: String)(implicit namespace: Namespace = Builder.globalNamespace): Unit =
    if (!_refmap.contains(id._id))
      overrideRefForId(id, namespace.name(name))

  def setFieldForId(parentid: Id, id: Id, name: String): Unit = {
    _refmap(id._id) = Slot(Alias(parentid), name)
  }

  def setIndexForId(parentid: Id, id: Id, index: Int): Unit =
    _refmap(id._id) = Index(Alias(parentid), index)

  def getRefForId(id: Id)(implicit namespace: Namespace = Builder.globalNamespace): Immediate = {
    setRefForId(id, s"T_${id._id}")
    _refmap(id._id)
  }
}

private object DynamicContext {
  val currentParentVar = new DynamicVariable[Option[Module]](None)
  val currentParamsVar = new DynamicVariable[Parameters](Parameters.empty)
  val currentCommandsVar = new DynamicVariable[ArrayBuffer[Command]](new ArrayBuffer[Command]())

  def getParentModule = currentParentVar.value
  def parentModuleScope[T](m: Option[Module])(body: => T): T = {
    currentParentVar.withValue(m)(body)
  }
  def forceParentModule[T](m: Module) {
    currentParentVar.value = Some(m)
  }

  def getParams: Parameters = currentParamsVar.value
  def paramsScope[T](p: Parameters)(body: => T): T = {
    currentParamsVar.withValue(p)(body)
  }

  def getCommands: Command = {
    val cmds = currentCommandsVar.value
    if (cmds.length == 0) EmptyCommand()
    else if (cmds.length == 1) cmds(0)
    else Begin(cmds.toList)
  }
  def pushCommand(c: Command) {
    currentCommandsVar.value += c
  }
  def commandsScope[T](body: => T): (T, Command) = {
    currentCommandsVar.withValue(new ArrayBuffer[Command]()){
      val r = body
      val c = getCommands
      (r, c)
    }
  }
}

private object Builder {
  val globalNamespace = new FIRRTLNamespace
  val globalRefMap = new ChiselRefMap
  val idGen = new IdGen

  val components = new ArrayBuffer[Component]()
  val commandz = new Stack[ArrayBuffer[Command]]()
  def commands = commandz.top
  def pushCommand(cmd: Command) = commands += cmd
  def commandify(cmds: ArrayBuffer[Command]): Command = {
    if (cmds.length == 0)
      EmptyCommand()
    else if (cmds.length == 1)
      cmds(0)
    else
      Begin(cmds.toList)
  }
  def pushCommands = 
    commandz.push(new ArrayBuffer[Command]())
  def popCommands: Command = {
    val newCommands = commands
    commandz.pop()
    commandify(newCommands)
  }
  def collectCommands[T <: Module](f: => T): (Command, T) = {
    pushCommands
    val mod = f
    (popCommands, mod)
  }

  def build[T <: Module](f: => T): Circuit = {
    val (cmd, mod) = collectCommands(f)
    Builder.globalRefMap.setRefForId(mod, mod.name)
    Circuit(components, components.last.name)
  }
}

object build {
  def apply[T <: Module](f: => T): Circuit = {
    Builder.build(f)
  }
}

import Builder.pushCommand
import Builder.pushCommands
import Builder.popCommands

/// CHISEL IR

case class PrimOp(val name: String) {
  override def toString = name
}

object PrimOp {
  val AddOp = PrimOp("add")
  val AddModOp = PrimOp("addw")
  val SubOp = PrimOp("sub")
  val SubModOp = PrimOp("subw")
  val TimesOp = PrimOp("mul")
  val DivideOp = PrimOp("div")
  val ModOp = PrimOp("mod")
  val ShiftLeftOp = PrimOp("shl")
  val ShiftRightOp = PrimOp("shr")
  val DynamicShiftLeftOp = PrimOp("dshl")
  val DynamicShiftRightOp = PrimOp("dshr")
  val BitAndOp = PrimOp("and")
  val BitOrOp = PrimOp("or")
  val BitXorOp = PrimOp("xor")
  val BitNotOp = PrimOp("not")
  val ConcatOp = PrimOp("cat")
  val BitSelectOp = PrimOp("bit")
  val BitsExtractOp = PrimOp("bits")
  val LessOp = PrimOp("lt")
  val LessEqOp = PrimOp("leq")
  val GreaterOp = PrimOp("gt")
  val GreaterEqOp = PrimOp("geq")
  val EqualOp = PrimOp("eq")
  val PadOp = PrimOp("pad")
  val NotEqualOp = PrimOp("neq")
  val NegOp = PrimOp("neg")
  val MultiplexOp = PrimOp("mux")
  val XorReduceOp = PrimOp("xorr")
  val ConvertOp = PrimOp("cvt")
  val AsUIntOp = PrimOp("asUInt")
  val AsSIntOp = PrimOp("asSInt")
}
import PrimOp._

abstract class Immediate {
  def fullname: String
  def name: String
  def debugName = fullname
}

abstract class Arg extends Immediate {
  def fullname: String
  def name: String
}

case class Alias(id: Id) extends Arg {
  def fullname = Builder.globalRefMap.getRefForId(id).fullname
  def name = Builder.globalRefMap.getRefForId(id).name
  override def debugName = Builder.globalRefMap.getRefForId(id).debugName
  def emit: String = "Alias(" + id + ")"
}

abstract class LitArg(val num: BigInt, widthArg: Width) extends Arg {
  private[Chisel] def forcedWidth = widthArg.known
  private[Chisel] def width: Width = if (forcedWidth) widthArg else Width(minWidth)

  protected def minWidth: Int
  if (forcedWidth)
    require(widthArg.get >= minWidth)
}

case class ULit(n: BigInt, w: Width) extends LitArg(n, w) {
  def fullname = name
  def name = "UInt<" + width + ">(\"h0" + num.toString(16) + "\")"
  def minWidth = 1 max n.bitLength

  require(n >= 0, s"UInt literal ${n} is negative")
}

case class SLit(n: BigInt, w: Width) extends LitArg(n, w) {
  def fullname = name
  def name = {
    val unsigned = if (n < 0) (BigInt(1) << width.get) + n else n
    s"asSInt(${ULit(unsigned, width).name})"
  }
  def minWidth = 1 + n.bitLength
}

case class Ref(val name: String) extends Immediate {
  def fullname = name
}
case class Slot(val imm: Immediate, val name: String) extends Immediate {
  def fullname =
    if (imm.fullname isEmpty) name else s"${imm.fullname}.${name}"
  override def debugName =
    if (imm.debugName isEmpty) name else s"${imm.debugName}.${name}"
}
case class Index(val imm: Immediate, val value: Int) extends Immediate {
  def name = "[" + value + "]"
  def fullname = imm.fullname + "[" + value + "]"
  override def debugName = imm.debugName + "." + value
}

case class Port(id: Data, kind: Kind)

object Width {
  def apply(x: Int): Width = KnownWidth(x)
  def apply(): Width = UnknownWidth()
}

sealed abstract class Width {
  type W = Int
  def max(that: Width): Width = this.op(that, _ max _)
  def + (that: Width): Width = this.op(that, _ + _)
  def + (that: Int): Width = this.op(this, (a, b) => a + that)
  def shiftRight(that: Int): Width = this.op(this, (a, b) => 0 max (a - that))
  def dynamicShiftLeft(that: Width): Width =
    this.op(that, (a, b) => a + (1 << b) - 1)

  def known: Boolean
  def get: W
  protected def op(that: Width, f: (W, W) => W): Width
}

sealed case class UnknownWidth() extends Width {
  def known = false
  def get = None.get
  def op(that: Width, f: (W, W) => W) = this
  override def toString = "?"
}

sealed case class KnownWidth(value: Int) extends Width {
  require(value >= 0)
  def known = true
  def get = value
  def op(that: Width, f: (W, W) => W) = that match {
    case KnownWidth(x) => KnownWidth(f(value, x))
    case _ => that
  }
  override def toString = value.toString
}

abstract class Kind(val isFlip: Boolean);
case class UnknownType(flip: Boolean) extends Kind(flip);
case class UIntType(val width: Width, flip: Boolean) extends Kind(flip);
case class SIntType(val width: Width, flip: Boolean) extends Kind(flip);
case class FloType(flip: Boolean) extends Kind(flip);
case class DblType(flip: Boolean) extends Kind(flip);
case class BundleType(val ports: Seq[Port], flip: Boolean) extends Kind(flip);
case class VectorType(val size: Int, val kind: Kind, flip: Boolean) extends Kind(flip);
case class ClockType(flip: Boolean) extends Kind(flip)

abstract class Command;
abstract class Definition extends Command {
  def id: Id
  def name = Builder.globalRefMap.getRefForId(id).name
}
case class DefUInt(id: Id, value: BigInt, width: Int) extends Definition
case class DefSInt(id: Id, value: BigInt, width: Int) extends Definition
case class DefFlo(id: Id, value: Float) extends Definition
case class DefDbl(id: Id, value: Double) extends Definition
case class DefPrim(id: Id, kind: Kind, op: PrimOp, args: Seq[Arg], lits: Seq[BigInt]) extends Definition
case class DefWire(id: Id, kind: Kind) extends Definition
case class DefRegister(id: Id, kind: Kind, clock: Clock, reset: Bool) extends Definition
case class DefMemory(id: Id, kind: Kind, size: Int, clock: Clock) extends Definition
case class DefSeqMemory(id: Id, kind: Kind, size: Int) extends Definition
case class DefAccessor(id: Id, source: Alias, direction: Direction, index: Arg) extends Definition
case class DefInstance(id: Module, module: String, ports: Seq[Port]) extends Definition
case class Conditionally(val prep: Command, val pred: Arg, val conseq: Command, var alt: Command) extends Command;
case class Begin(val body: List[Command]) extends Command();
case class Connect(val loc: Alias, val exp: Arg) extends Command;
case class BulkConnect(val loc1: Alias, val loc2: Alias) extends Command;
case class ConnectInit(val loc: Alias, val exp: Arg) extends Command;
case class ConnectInitIndex(val loc: Alias, val index: Int, val exp: Arg) extends Command;
case class EmptyCommand() extends Command;

case class Component(val name: String, val ports: Seq[Port], val body: Command);
case class Circuit(val components: Seq[Component], val main: String);

object Commands {
  val NoLits = Seq[BigInt]()
}

import Commands._

/// COMPONENTS

sealed abstract class Direction(name: String) {
  override def toString = name
  def flip: Direction
}
object INPUT  extends Direction("input") { def flip = OUTPUT }
object OUTPUT extends Direction("output") { def flip = INPUT }
object NO_DIR extends Direction("?") { def flip = NO_DIR }

/// CHISEL FRONT-END

trait Id {
  private[Chisel] val _id = Builder.idGen.next
}

object debug {
  // TODO:
  def apply (arg: Data) = arg
}

abstract class Data(dirArg: Direction) extends Id {
  private[Chisel] val _mod: Module = DynamicContext.getParentModule.getOrElse(
    throwException("Data subclasses can only be instantiated inside Modules!"))
  //TODO: is this true?

  _mod.addNode(Some(this))
  def params = DynamicContext.getParams

  def toType: Kind
  def dir: Direction = dirVar

  // Sucks this is mutable state, but cloneType doesn't take a Direction arg
  private var isFlipVar = dirArg == INPUT
  private[Chisel] var dirVar = dirArg
  private[Chisel] def isFlip = isFlipVar

  private def cloneWithDirection(newDir: Direction => Direction,
                                 newFlip: Boolean => Boolean): this.type = {
    val res = this.cloneType
    res.isFlipVar = newFlip(res.isFlipVar)
    for ((me, it) <- this.flatten zip res.flatten) {
      it.dirVar = newDir(me.dirVar)
    }
    res
  }
  def asInput: this.type = cloneWithDirection(_ => INPUT, _ => true)
  def asOutput: this.type = cloneWithDirection(_ => OUTPUT, _ => false)
  def flip(): this.type = cloneWithDirection(_.flip, !_)

  private[Chisel] def badConnect(that: Data): Unit =
    throwException(s"cannot connect ${this} and ${that}")
  private[Chisel] def connect(that: Data): Unit =
    pushCommand(Connect(this.lref, that.ref))
  private[Chisel] def bulkConnect(that: Data): Unit =
    pushCommand(BulkConnect(this.lref, that.lref))
  private[Chisel] def collectElts = { }
  private[Chisel] def lref: Alias = Alias(this)
  private[Chisel] def ref: Arg = if (isLit) litArg() else lref
  private[Chisel] def debugName = _mod.debugName + "." + Builder.globalRefMap.getRefForId(this).debugName
  private[Chisel] def cloneTypeWidth(width: Width): this.type

  def := (that: Data): Unit = this badConnect that
  def <> (that: Data): Unit = this badConnect that
  def cloneType: this.type
  def name = Builder.globalRefMap.getRefForId(this).name
  def litArg(): LitArg = None.get
  def litValue(): BigInt = None.get
  def isLit(): Boolean = false
  def floLitValue: Float = intBitsToFloat(litValue().toInt)
  def dblLitValue: Double = longBitsToDouble(litValue().toLong)

  def width: Width
  final def getWidth = width.get

  def flatten: IndexedSeq[Bits]
  def fromBits(n: Bits): this.type = {
    var i = 0
    val wire = Wire(this.cloneType)
    for (x <- wire.flatten) {
      x := n(i + x.getWidth-1, i)
      i += x.getWidth
    }
    wire.asInstanceOf[this.type]
  }
  def toBits(): UInt = {
    val elts = this.flatten.reverse
    Cat(elts.head, elts.tail:_*).asUInt
  }

  def toPort: Port = Port(this, toType)
}

object Wire {
  def apply[T <: Data](t: T = null, init: T = null): T = {
    val x = Reg.makeType(t, null.asInstanceOf[T], init)
    pushCommand(DefWire(x, x.toType))
    if (init != null)
      x := init
    else
      x.flatten.foreach(e => e := e.makeLit(0))
    x
  }
}

object Reg {
  private[Chisel] def makeType[T <: Data](t: T = null, next: T = null, init: T = null): T = {
    if (t ne null) t.cloneType
    else if (next ne null) next.cloneTypeWidth(Width())
    else if (init ne null) {
      if (init.isLit && init.litArg.forcedWidth) init.cloneType
      else init.cloneTypeWidth(Width())
    } else throwException("cannot infer type")
  }

  def apply[T <: Data](t: T = null, next: T = null, init: T = null): T = {
    val x = makeType(t, next, init)
    pushCommand(DefRegister(x, x.toType, x._mod.clock, x._mod.reset)) // TODO multi-clock
    if (init != null)
      pushCommand(ConnectInit(x.lref, init.ref))
    if (next != null) 
      x := next
    x
  }
  def apply[T <: Data](outType: T): T = Reg[T](outType, null.asInstanceOf[T], null.asInstanceOf[T])
}

object Mem {
  def apply[T <: Data](t: T, size: Int): Mem[T] = {
    val mt  = t.cloneType
    val mem = new Mem(mt, size)
    pushCommand(DefMemory(mt, mt.toType, size, mt._mod.clock)) // TODO multi-clock
    mem
  }
}

class Mem[T <: Data](protected[Chisel] val t: T, val length: Int) extends VecLike[T] {
  def apply(idx: Int): T = apply(UInt(idx))
  def apply(idx: UInt): T = {
    val x = t.cloneType
    pushCommand(DefAccessor(x, Alias(t), NO_DIR, idx.ref))
    x
  }

  def read(idx: UInt): T = apply(idx)
  def write(idx: UInt, data: T): Unit = apply(idx) := data
  def write(idx: UInt, data: T, mask: T): Unit = {
    // This is totally fucked, but there's no true write mask support yet
    val mask1 = mask.toBits
    write(idx, t.fromBits((read(idx).toBits & ~mask1) | (data.toBits & mask1)))
  }

  //TODO: is this correct?
  def name = Builder.globalRefMap.getRefForId(t).name
  def debugName = Builder.globalRefMap.getRefForId(t).debugName
}

object SeqMem {
  def apply[T <: Data](t: T, size: Int): SeqMem[T] =
    new SeqMem(t, size)
}

// For now, implement SeqMem in terms of Mem
class SeqMem[T <: Data](t: T, n: Int) {
  private val mem = Mem(t, n)

  def read(addr: UInt): T = mem.read(Reg(next = addr))
  def read(addr: UInt, enable: Bool): T = mem.read(RegEnable(addr, enable))

  def write(addr: UInt, data: T): Unit = mem.write(addr, data)
  def write(addr: UInt, data: T, mask: T): Unit = mem.write(addr, data, mask)
}

object Vec {
  def apply[T <: Data](gen: T, n: Int): Vec[T] = {
    if (gen.isLit) apply(Seq.fill(n)(gen))
    else new Vec(gen.cloneType, n)
  }
  def apply[T <: Data](elts: Seq[T]): Vec[T] = {
    require(!elts.isEmpty)
    val width = elts.map(_.width).reduce(_ max _)
    val vec = new Vec(elts.head.cloneTypeWidth(width), elts.length)
    pushCommand(DefWire(vec, vec.toType))
    for ((v, e) <- vec zip elts)
      v := e
    vec
  }
  def apply[T <: Data](elt0: T, elts: T*): Vec[T] =
    apply(elt0 +: elts.toSeq)
  def tabulate[T <: Data](n: Int)(gen: (Int) => T): Vec[T] = 
    apply((0 until n).map(i => gen(i)))
  def fill[T <: Data](n: Int)(gen: => T): Vec[T] = 
    apply(gen, n)
}

abstract class Aggregate(dirArg: Direction) extends Data(dirArg) {
  def cloneTypeWidth(width: Width): this.type = cloneType
  def width: Width = flatten.map(_.width).reduce(_ + _)
}

class Vec[T <: Data](gen: => T, val length: Int)
    extends Aggregate(gen.dir) with VecLike[T] {
  private val self = IndexedSeq.fill(length)(gen)

  override def collectElts: Unit =
    for ((e, i) <- self zipWithIndex)
      Builder.globalRefMap.setIndexForId(this, e, i)

  override def <> (that: Data): Unit = that match {
    case _: Vec[_] => this bulkConnect that
    case _ => this badConnect that
  }

  def <> (that: Seq[T]): Unit =
    for ((a, b) <- this zip that)
      a <> b

  def <> (that: Vec[T]): Unit = this bulkConnect that

  override def := (that: Data): Unit = that match {
    case _: Vec[_] => this connect that
    case _ => this badConnect that
  }

  def := (that: Seq[T]): Unit = {
    require(this.length == that.length)
    for ((a, b) <- this zip that)
      a := b
  }

  def := (that: Vec[T]): Unit = this connect that

  def apply(idx: UInt): T = {
    val x = gen
    pushCommand(DefAccessor(x, Alias(this), NO_DIR, idx.ref))
    x
  }

  def apply(idx: Int): T = self(idx)

  def toType: Kind = VectorType(length, gen.toType, isFlip)

  override def cloneType: this.type =
    Vec(gen, length).asInstanceOf[this.type]

  override lazy val flatten: IndexedSeq[Bits] =
    (0 until length).flatMap(i => this.apply(i).flatten)

  def read(idx: UInt): T = apply(idx)
  def write(idx: UInt, data: T): Unit = apply(idx) := data
}

trait VecLike[T <: Data] extends collection.IndexedSeq[T] {
  def read(idx: UInt): T
  def write(idx: UInt, data: T): Unit
  def apply(idx: UInt): T

  def forall(p: T => Bool): Bool = (this map p).fold(Bool(true))(_&&_)
  def exists(p: T => Bool): Bool = (this map p).fold(Bool(false))(_||_)
  def contains(x: T) (implicit evidence: T <:< UInt): Bool = this.exists(_ === x)
  def count(p: T => Bool): UInt = PopCount((this map p).toSeq)

  private def indexWhereHelper(p: T => Bool) = this map p zip (0 until length).map(i => UInt(i))
  def indexWhere(p: T => Bool): UInt = PriorityMux(indexWhereHelper(p))
  def lastIndexWhere(p: T => Bool): UInt = PriorityMux(indexWhereHelper(p).reverse)
  def onlyIndexWhere(p: T => Bool): UInt = Mux1H(indexWhereHelper(p))
}

object BitPat {
  private def parse(x: String): (BigInt, BigInt, Int) = {
    require(x.head == 'b', "BINARY BitPats ONLY")
    var bits = BigInt(0)
    var mask = BigInt(0)
    for (d <- x.tail) {
      if (d != '_') {
        if (!"01?".contains(d)) ChiselError.error({"Literal: " + x + " contains illegal character: " + d})
        mask = (mask << 1) + (if (d == '?') 0 else 1)
        bits = (bits << 1) + (if (d == '1') 1 else 0)
      }
    }
    (bits, mask, x.length-1)
  }

  def apply(n: String): BitPat = {
    val (bits, mask, width) = parse(n)
    new BitPat(bits, mask, width)
  }

  def DC(width: Int): BitPat = BitPat("b" + ("?" * width))

  // BitPat <-> UInt
  implicit def BitPatToUInt(x: BitPat): UInt = {
    require(x.mask == (BigInt(1) << x.getWidth)-1)
    UInt(x.value, x.getWidth)
  }
  implicit def apply(x: UInt): BitPat = {
    require(x.isLit)
    BitPat("b" + x.litValue.toString(2))
  }
}

class BitPat(val value: BigInt, val mask: BigInt, width: Int) {
  def getWidth: Int = width
  def === (other: UInt): Bool = UInt(value) === (other & UInt(mask))
  def != (other: UInt): Bool = !(this === other)
}

abstract class Element(dirArg: Direction, val width: Width) extends Data(dirArg)

object Clock {
  def apply(dir: Direction = NO_DIR): Clock = new Clock(dir)
}

sealed class Clock(dirArg: Direction) extends Element(dirArg, Width(1)) {
  def cloneType: this.type = Clock(dirArg).asInstanceOf[this.type]
  def cloneTypeWidth(width: Width): this.type = cloneType
  def flatten: IndexedSeq[Bits] = IndexedSeq()
  def toType: Kind = ClockType(isFlip)

  override def := (that: Data): Unit = that match {
    case _: Clock => this connect that
    case _ => this badConnect that
  }
}

sealed abstract class Bits(dirArg: Direction, width: Width, lit: Option[LitArg]) extends Element(dirArg, width) {
  override def litArg(): LitArg = lit.get
  override def isLit(): Boolean = lit.isDefined
  override def litValue(): BigInt = lit.get.num
  def fromInt(x: BigInt): this.type = makeLit(x)
  def makeLit(value: BigInt): this.type
  def cloneType: this.type = cloneTypeWidth(width)

  override def flatten: IndexedSeq[Bits] = IndexedSeq(this)

  override def <> (that: Data): Unit = this := that

  final def apply(x: BigInt): Bool = {
    if (isLit()) Bool((litValue() >> x.toInt) & 1)
    else {
      val d = Bool()
      pushCommand(DefPrim(d, d.toType, BitSelectOp, Seq(this.ref), Seq(x)))
      d
    }
  }
  final def apply(x: Int): Bool =
    apply(BigInt(x))
  final def apply(x: UInt): Bool =
    (this >> x)(0)

  final def apply(x: BigInt, y: BigInt): UInt = {
    val w = (x - y + 1).toInt
    if (isLit()) UInt((litValue >> y.toInt) & ((BigInt(1) << w) - 1), w)
    else {
      val d = UInt(width = w)
      pushCommand(DefPrim(d, d.toType, BitsExtractOp, Seq(this.ref), Seq(x, y)))
      d
    }
  }
  final def apply(x: Int, y: Int): UInt =
    apply(BigInt(x), BigInt(y))

  private[Chisel] def unop(op: PrimOp, width: Width): this.type = {
    val d = cloneTypeWidth(width)
    pushCommand(DefPrim(d, d.toType, op, Seq(this.ref), NoLits))
    d
  }
  private[Chisel] def binop(op: PrimOp, other: BigInt, width: Width): this.type = {
    val d = cloneTypeWidth(width)
    pushCommand(DefPrim(d, d.toType, op, Seq(this.ref), Seq(other)))
    d
  }
  private[Chisel] def binop(op: PrimOp, other: Bits, width: Width): this.type = {
    val d = cloneTypeWidth(width)
    pushCommand(DefPrim(d, d.toType, op, Seq(this.ref, other.ref), NoLits))
    d
  }
  private[Chisel] def compop(op: PrimOp, other: Bits): Bool = {
    val d = new Bool(NO_DIR)
    pushCommand(DefPrim(d, d.toType, op, Seq(this.ref, other.ref), NoLits))
    d
  }
  private[Chisel] def unimp(op: String) =
    throwException(s"Operator ${op} unsupported for class ${getClass}")
  private[Chisel] def redop(op: PrimOp): Bool = {
    val d = new Bool(NO_DIR)
    pushCommand(DefPrim(d, d.toType, op, Seq(this.ref), NoLits))
    d
  }

  def unary_~ : this.type = unop(BitNotOp, width)
  def pad (other: Int): this.type = binop(PadOp, other, Width(other))

  def << (other: BigInt): Bits
  def << (other: Int): Bits
  def << (other: UInt): Bits
  def >> (other: BigInt): Bits
  def >> (other: Int): Bits
  def >> (other: UInt): Bits

  def toBools: Vec[Bool] = Vec.tabulate(this.getWidth)(i => this(i))

  def asSInt(): SInt
  def asUInt(): UInt
  def toSInt(): SInt
  def toUInt(): UInt
  def toBool(): Bool = this(0)

  override def toBits = asUInt
  override def fromBits(n: Bits): this.type = {
    val res = Wire(this).asInstanceOf[this.type]
    res := n
    res
  }
}

abstract trait Num[T <: Data] {
  // def << (b: T): T;
  // def >> (b: T): T;
  //def unary_-(): T;
  def +  (b: T): T;
  def *  (b: T): T;
  def /  (b: T): T;
  def %  (b: T): T;
  def -  (b: T): T;
  def <  (b: T): Bool;
  def <= (b: T): Bool;
  def >  (b: T): Bool;
  def >= (b: T): Bool;

  def min(b: T): T = Mux(this < b, this.asInstanceOf[T], b)
  def max(b: T): T = Mux(this < b, b, this.asInstanceOf[T])
}

sealed class UInt(dir: Direction, width: Width, lit: Option[ULit] = None) extends Bits(dir, width, lit) with Num[UInt] {
  override def cloneTypeWidth(w: Width): this.type =
    new UInt(dir, w).asInstanceOf[this.type]

  def toType: Kind = UIntType(width, isFlip)

  override def makeLit(value: BigInt): this.type =
    UInt(value).asInstanceOf[this.type]

  override def := (that: Data): Unit = that match {
    case _: UInt => this connect that
    case _ => this badConnect that
  }

  def unary_- = UInt(0) - this
  def unary_-% = UInt(0) -% this
  def +& (other: UInt): UInt = binop(AddOp, other, (this.width max other.width) + 1)
  def + (other: UInt): UInt = this +% other
  def +% (other: UInt): UInt = binop(AddModOp, other, this.width max other.width)
  def -& (other: UInt): UInt = binop(SubOp, other, (this.width max other.width) + 1)
  def - (other: UInt): UInt = this -% other
  def -% (other: UInt): UInt = binop(SubModOp, other, this.width max other.width)
  def * (other: UInt): UInt = binop(TimesOp, other, this.width + other.width)
  def * (other: SInt): SInt = other * this
  def / (other: UInt): UInt = binop(DivideOp, other, this.width)
  def % (other: UInt): UInt = binop(ModOp, other, this.width)

  def & (other: UInt): UInt = binop(BitAndOp, other, this.width max other.width)
  def | (other: UInt): UInt = binop(BitOrOp, other, this.width max other.width)
  def ^ (other: UInt): UInt = binop(BitXorOp, other, this.width max other.width)
  def ## (other: UInt): UInt = Cat(this, other)

  def orR = this != UInt(0)
  def andR = ~this === UInt(0)
  def xorR = redop(XorReduceOp)

  def < (other: UInt): Bool = compop(LessOp, other)
  def > (other: UInt): Bool = compop(GreaterOp, other)
  def <= (other: UInt): Bool = compop(LessEqOp, other)
  def >= (other: UInt): Bool = compop(GreaterEqOp, other)
  def != (other: UInt): Bool = compop(NotEqualOp, other)
  def === (other: UInt): Bool = compop(EqualOp, other)
  def unary_! : Bool = this === Bits(0)

  def << (other: Int): UInt = binop(ShiftLeftOp, other, this.width + other)
  def << (other: BigInt): UInt = this << other.toInt
  def << (other: UInt): UInt = binop(DynamicShiftLeftOp, other, this.width.dynamicShiftLeft(other.width))
  def >> (other: Int): UInt = binop(ShiftRightOp, other, this.width.shiftRight(other))
  def >> (other: BigInt): UInt = this >> other.toInt
  def >> (other: UInt): UInt = binop(DynamicShiftRightOp, other, this.width)

  def bitSet(off: UInt, dat: Bool): UInt = {
    val bit = UInt(1, 1) << off
    Mux(dat, this | bit, ~(~this | bit))
  }

  def === (that: BitPat): Bool = that === this
  def != (that: BitPat): Bool = that != this

  def zext(): SInt = {
    val x = SInt(NO_DIR, width + 1)
    pushCommand(DefPrim(x, x.toType, ConvertOp, Seq(ref), NoLits))
    x
  }

  def asSInt(): SInt = {
    val x = SInt(NO_DIR, width)
    pushCommand(DefPrim(x, x.toType, AsSIntOp, Seq(ref), NoLits))
    x
  }

  def toSInt(): SInt = asSInt()
  def toUInt(): UInt = this
  def asUInt(): UInt = this
}

trait UIntFactory {
  def apply(): UInt = apply(NO_DIR, Width())
  def apply(dir: Direction): UInt = apply(dir, Width())
  def apply(dir: Direction = NO_DIR, width: Int): UInt = apply(dir, Width(width))
  def apply(dir: Direction, width: Width): UInt = new UInt(dir, width)

  def apply(value: BigInt): UInt = apply(value, Width())
  def apply(value: BigInt, width: Int): UInt = apply(value, Width(width))
  def apply(value: BigInt, width: Width): UInt = {
    val lit = ULit(value, width)
    new UInt(NO_DIR, lit.width, Some(lit))
  }
  def apply(n: String, width: Int): UInt = apply(parse(n), width)
  def apply(n: String): UInt = apply(parse(n), parsedWidth(n))

  private def parse(n: String) =
    Literal.stringToVal(n(0), n.substring(1, n.length))
  private def parsedWidth(n: String) =
    if (n(0) == 'b') Width(n.length-1)
    else if (n(0) == 'h') Width((n.length-1) * 4)
    else Width()
}

// Bits constructors are identical to UInt constructors.
object Bits extends UIntFactory
object UInt extends UIntFactory

sealed class SInt(dir: Direction, width: Width, lit: Option[SLit] = None) extends Bits(dir, width, lit) with Num[SInt] {
  override def cloneTypeWidth(w: Width): this.type =
    new SInt(dir, w).asInstanceOf[this.type]
  def toType: Kind = SIntType(width, isFlip)

  override def := (that: Data): Unit = that match {
    case _: SInt => this badConnect that
    case _ => this badConnect that
  }

  override def makeLit(value: BigInt): this.type =
    SInt(value).asInstanceOf[this.type]

  def unary_- : SInt = SInt(0, getWidth) - this
  def unary_-% : SInt = SInt(0, getWidth) -% this
  def +& (other: SInt): SInt = binop(AddOp, other, (this.width max other.width) + 1)
  def +% (other: SInt): SInt = binop(AddModOp, other, this.width max other.width)
  def + (other: SInt): SInt = this +% other
  def -& (other: SInt): SInt = binop(SubOp, other, (this.width max other.width) + 1)
  def -% (other: SInt): SInt = binop(SubModOp, other, this.width max other.width)
  def - (other: SInt): SInt = this -% other
  def * (other: SInt): SInt = binop(TimesOp, other, this.width + other.width)
  def * (other: UInt): SInt = binop(TimesOp, other, this.width + other.width)
  def / (other: SInt): SInt = binop(DivideOp, other, this.width)
  def % (other: SInt): SInt = binop(ModOp, other, this.width)

  def & (other: SInt): SInt = binop(BitAndOp, other, this.width max other.width)
  def | (other: SInt): SInt = binop(BitOrOp, other, this.width max other.width)
  def ^ (other: SInt): SInt = binop(BitXorOp, other, this.width max other.width)

  def < (other: SInt): Bool = compop(LessOp, other)
  def > (other: SInt): Bool = compop(GreaterOp, other)
  def <= (other: SInt): Bool = compop(LessEqOp, other)
  def >= (other: SInt): Bool = compop(GreaterEqOp, other)
  def != (other: SInt): Bool = compop(NotEqualOp, other)
  def === (other: SInt): Bool = compop(EqualOp, other)
  def abs(): UInt = Mux(this < SInt(0), (-this).toUInt, this.toUInt)

  def << (other: Int): SInt = binop(ShiftLeftOp, other, this.width + other)
  def << (other: BigInt): SInt = this << other.toInt
  def << (other: UInt): SInt = binop(DynamicShiftLeftOp, other, this.width.dynamicShiftLeft(other.width))
  def >> (other: Int): SInt = binop(ShiftRightOp, other, this.width.shiftRight(other))
  def >> (other: BigInt): SInt = this >> other.toInt
  def >> (other: UInt): SInt = binop(DynamicShiftRightOp, other, this.width)

  def asUInt(): UInt = {
    val x = UInt(NO_DIR, width)
    pushCommand(DefPrim(x, x.toType, AsUIntOp, Seq(ref), NoLits))
    x
  }
  def toUInt(): UInt = asUInt()
  def asSInt(): SInt = this
  def toSInt(): SInt = this
}

object SInt {
  def apply(): SInt = apply(NO_DIR, Width())
  def apply(dir: Direction): SInt = apply(dir, Width())
  def apply(dir: Direction = NO_DIR, width: Int): SInt = apply(dir, Width(width))
  def apply(dir: Direction, width: Width): SInt = new SInt(dir, width)

  def apply(value: BigInt): SInt = apply(value, Width())
  def apply(value: BigInt, width: Int): SInt = apply(value, Width(width))
  def apply(value: BigInt, width: Width): SInt = {
    val lit = SLit(value, width)
    new SInt(NO_DIR, lit.width, Some(lit))
  }
}

sealed class Bool(dir: Direction, lit: Option[ULit] = None) extends UInt(dir, Width(1), lit) {
  override def cloneTypeWidth(w: Width): this.type = new Bool(dir).asInstanceOf[this.type]

  override def makeLit(value: BigInt): this.type =
    Bool(value).asInstanceOf[this.type]

  def & (other: Bool): Bool = super.&(other).asInstanceOf[Bool]
  def | (other: Bool): Bool = super.|(other).asInstanceOf[Bool]
  def ^ (other: Bool): Bool = super.^(other).asInstanceOf[Bool]

  def || (that: Bool): Bool = this | that
  def && (that: Bool): Bool = this & that

  require(lit.isEmpty || lit.get.num < 2)
}
object Bool {
  def apply(dir: Direction = NO_DIR) : Bool =
    new Bool(dir)
  def apply(value: BigInt) =
    new Bool(NO_DIR, Some(ULit(value, Width(1))))
  def apply(value: Boolean) : Bool = apply(if (value) 1 else 0)
}

object Mux {
  def apply[T <: Data](cond: Bool, con: T, alt: T): T = (con, alt) match {
    case (c: Bits, a: Bits) => doMux(cond, c, a).asInstanceOf[T]
    case _ => doWhen(cond, con, alt)
  }

  // These implementations are type-unsafe and rely on FIRRTL for type checking
  private def doMux[T <: Bits](cond: Bool, con: T, alt: T): T = {
    val d = alt.cloneTypeWidth(con.width max alt.width)
    pushCommand(DefPrim(d, d.toType, MultiplexOp, Seq(cond.ref, con.ref, alt.ref), NoLits))
    d
  }
  // This returns an lvalue, which it most definitely should not
  private def doWhen[T <: Data](cond: Bool, con: T, alt: T): T = {
    val res = Wire(alt, init = alt)
    when (cond) { res := con }
    res
  }
}

object Cat {
  def apply[T <: Bits](a: T, r: T*): UInt = apply(a :: r.toList)
  def apply[T <: Bits](r: Seq[T]): UInt = {
    if (r.tail.isEmpty) r.head.asUInt
    else {
      val left = apply(r.slice(0, r.length/2))
      val right = apply(r.slice(r.length/2, r.length))
      val w = left.width + right.width
      if (left.isLit && right.isLit) {
        UInt((left.litValue() << right.getWidth) | right.litValue(), w)
      } else {
        val d = UInt(NO_DIR, w)
        pushCommand(DefPrim(d, d.toType, ConcatOp, Seq(left.ref, right.ref), NoLits))
        d
      }
    }
  }
}

object Bundle {
  val keywords = HashSet[String]("flip", "asInput", "asOutput",
    "cloneType", "clone", "toBits")
  def apply[T <: Bundle](b: => T)(implicit p: Parameters): T = {
    DynamicContext.paramsScope(p.push){ b }
  }
  def apply[T <: Bundle](b: => T,  f: PartialFunction[Any,Any]): T = {
    val q = DynamicContext.getParams.alterPartial(f)
    apply(b)(q)
  }
}

class Bundle extends Aggregate(NO_DIR) {
  private implicit val _namespace = new ChildNamespace(Builder.globalNamespace)

  override def <> (that: Data): Unit = that match {
    case _: Bundle => this bulkConnect that
    case _ => this badConnect that
  }

  override def := (that: Data): Unit = this <> that

  def toPorts: Seq[Port] =
    elements.map(_._2.toPort).toSeq.reverse
  def toType: BundleType = 
    BundleType(this.toPorts, isFlip)

  override def flatten: IndexedSeq[Bits] =
    allElts.flatMap(_._2.flatten)

  lazy val elements: ListMap[String, Data] = ListMap(allElts:_*)

  private def isBundleField(m: java.lang.reflect.Method) =
    m.getParameterTypes.isEmpty && !isStatic(m.getModifiers) &&
    classOf[Data].isAssignableFrom(m.getReturnType) &&
    !(Bundle.keywords contains m.getName)

  private lazy val allElts = {
    val elts = ArrayBuffer[(String, Data)]()
    for (m <- getClass.getMethods; if isBundleField(m)) m.invoke(this) match {
      case data: Data => elts += m.getName -> data
      case _ =>
    }
    elts sortWith {case ((an, a), (bn, b)) => (a._id > b._id) || ((a eq b) && (an > bn))}
  }

  private[Chisel] lazy val namedElts = LinkedHashMap[String, Data](allElts:_*)

  private[Chisel] def addElt(name: String, elt: Data) =
    namedElts += name -> elt

  override def collectElts =
    namedElts.foreach {case(name, elt) => Builder.globalRefMap.setFieldForId(this, elt, name)}

  override def cloneType : this.type = {
    try {
      val constructor = this.getClass.getConstructors.head
      val res = constructor.newInstance(Seq.fill(constructor.getParameterTypes.size)(null):_*)
      res.asInstanceOf[this.type]
    } catch {
      case npe: java.lang.reflect.InvocationTargetException if npe.getCause.isInstanceOf[java.lang.NullPointerException] =>
        ChiselError.error(s"Parameterized Bundle ${this.getClass} needs cloneType method. You are probably using an anonymous Bundle object that captures external state and hence is un-cloneTypeable")
        this
      case e: java.lang.Exception =>
        ChiselError.error(s"Parameterized Bundle ${this.getClass} needs cloneType  method")
        this
    }
  }
}

object Module {
  def apply[T <: Module](bc: => T)(implicit currParams: Parameters = DynamicContext.getParams): T = {
    DynamicContext.paramsScope(currParams.push) {
      val m = DynamicContext.parentModuleScope(None) { bc.setRefs() }
      val ports = m.computePorts
      Builder.components += Component(m.name, ports, popCommands)
      pushCommand(DefInstance(m, m.name, ports))
      m
    }.connectImplicitIOs()
  }
  def apply[T <: Module](m: => T, f: PartialFunction[Any,Any]): T = {
    val q = DynamicContext.getParams.alterPartial(f)
    apply(m)(q)
  }
}

abstract class Module(_clock: Clock = null, _reset: Bool = null) extends Id {
  private val _parent = DynamicContext.getParentModule
  private val _nodes = ArrayBuffer[Data]()
  private implicit val _namespace = new ChildNamespace(Builder.globalNamespace)
  val name = Builder.globalNamespace.name(getClass.getName.split('.').last)

  DynamicContext.forceParentModule(this)
  pushCommands

  private def params = DynamicContext.getParams
  params.path = this.getClass :: params.path //TODO: make immutable?

  def io: Bundle
  val clock = Clock(INPUT)
  val reset = Bool(INPUT)

  private[Chisel] def ref = Builder.globalRefMap.getRefForId(this)
  private[Chisel] def lref = ref

  def addNode(d: Option[Data]) { d.map(_nodes += _) }

  def debugName: String = _parent match {
    case Some(p) => s"${p.debugName}.${ref.debugName}"
    case None => ref.debugName
  }

  private def computePorts =
    clock.toPort +: reset.toPort +: io.toPorts

  private def connectImplicitIOs(): this.type = _parent match {
    case Some(p) =>
      clock := (if (_clock eq null) p.clock else _clock)
      reset := (if (_reset eq null) p.reset else _reset)
      this
    case None => this
  }

  private def makeImplicitIOs(): this.type  = {
    io.addElt("clock", clock)
    io.addElt("reset", reset)
    this
  }

  private def setRefs(): this.type = {
    val valNames = HashSet[String](getClass.getDeclaredFields.map(_.getName):_*)
    def isPublicVal(m: java.lang.reflect.Method) =
      m.getParameterTypes.isEmpty && valNames.contains(m.getName)

    makeImplicitIOs
    _nodes.foreach(_.collectElts)

    // FIRRTL: the IO namespace is part of the module namespace
    Builder.globalRefMap.setRefForId(io, "")
    for ((name, field) <- io.namedElts)
      _namespace.name(name)

    val methods = getClass.getMethods.sortWith(_.getName > _.getName)
    for (m <- methods; if isPublicVal(m)) {
      m.invoke(this) match {
        case module: Module =>
          Builder.globalRefMap.setRefForId(module, m.getName)
        case mem: Mem[_] =>
          Builder.globalRefMap.setRefForId(mem.t, m.getName)
        case vec: Vec[_] =>
          Builder.globalRefMap.setRefForId(vec, m.getName)
        case data: Data =>
          Builder.globalRefMap.setRefForId(data, m.getName)
        case _ =>
      }
    }
    this
  }

  // TODO: actually implement these
  def assert(cond: Bool, msg: String): Unit = {}
  def printf(message: String, args: Bits*): Unit = {}
}

// TODO: actually implement BlackBox (this hack just allows them to compile)
abstract class BlackBox(_clock: Clock = null, _reset: Bool = null) extends Module(_clock = _clock, _reset = _reset) {
  def setVerilogParameters(s: String): Unit = {}
}

object when {
  private[Chisel] def execBlock(block: => Unit): Command = {
    pushCommands
    block
    val cmd = popCommands
    cmd
  }
  def apply(cond: => Bool)(block: => Unit): when = {
    new when(cond)( block )
  }
}

class when(cond: => Bool)(block: => Unit) {
  def elsewhen (cond: => Bool)(block: => Unit): when = {
    pushCommands
    val res = new when(cond) ( block )
    this.cmd.alt = popCommands
    res
  }

  def otherwise (block: => Unit) {
   this.cmd.alt = when.execBlock(block)
  }

  // Capture any commands we need to set up the conditional test.
  pushCommands
  val pred = cond.ref
  val prep = popCommands
  val conseq  = when.execBlock(block)
  // Assume we have an empty alternate clause.
  //  elsewhen and otherwise will update it if that isn't the case.
  val cmd = Conditionally(prep, pred, conseq, EmptyCommand())
  pushCommand(cmd)
}


/// CHISEL IR EMITTER

class Emitter {
  private var indenting = 0
  def withIndent(f: => String) = {
    indenting += 1
    val res = f
    indenting -= 1
    res
  }

  def newline = "\n" + ("  " * indenting)
  def join(parts: Seq[String], sep: String): StringBuilder =
    parts.tail.foldLeft(new StringBuilder(parts.head))((s, p) => s ++= sep ++= p)
  def join0(parts: Seq[String], sep: String): StringBuilder =
    parts.foldLeft(new StringBuilder)((s, p) => s ++= sep ++= p)
  def emitDir(e: Port, isTop: Boolean): String =
    if (isTop) (if (e.id.isFlip) "input " else "output ")
    else (if (e.id.isFlip) "flip " else "")
  def emit(e: PrimOp): String = e.name
  def emit(e: Arg): String = e.fullname
  def emitPort(e: Port, isTop: Boolean): String =
    s"${emitDir(e, isTop)}${Builder.globalRefMap.getRefForId(e.id).name} : ${emitType(e.kind)}"
  def emitType(e: Kind): String = {
    e match {
      case e: UnknownType => "?"
      case e: UIntType => s"UInt<${e.width}>"
      case e: SIntType => s"SInt<${e.width}>"
      case e: BundleType => s"{${join(e.ports.map(x => emitPort(x, false)), ", ")}}"
      case e: VectorType => s"${emitType(e.kind)}[${e.size}]"
      case e: ClockType => s"Clock"
    }
  }
  def emit(e: Command): String = e match {
    case e: DefUInt => s"node ${e.name} = UInt<${e.width}>(${e.value})"
    case e: DefSInt => s"node ${e.name} = SInt<${e.width}>(${e.value})"
    case e: DefFlo => s"node ${e.name} = Flo(${e.value})"
    case e: DefDbl => s"node ${e.name} = Dbl(${e.value})"
    case e: DefPrim => s"node ${e.name} = ${emit(e.op)}(${join(e.args.map(x => emit(x)) ++ e.lits.map(x => x.toString), ", ")})"
    case e: DefWire => s"wire ${e.name} : ${emitType(e.kind)}"
    case e: DefRegister => s"reg ${e.name} : ${emitType(e.kind)}, ${e.clock.name}, ${e.reset.name}"
    case e: DefMemory => s"cmem ${e.name} : ${emitType(e.kind)}[${e.size}], ${e.clock.name}";
    case e: DefSeqMemory => s"smem ${e.name} : ${emitType(e.kind)}[${e.size}]";
    case e: DefAccessor => s"infer accessor ${e.name} = ${emit(e.source)}[${emit(e.index)}]"
    case e: DefInstance => {
      val mod = e.id
      // update all references to the modules ports
       Builder.globalRefMap.overrideRefForId(mod.io, e.name)
      "inst " + e.name + " of " + e.module + newline + join0(e.ports.flatMap(x => initPort(x, INPUT)), newline)
    }
    case e: Conditionally => {
      val prefix = if (!e.prep.isInstanceOf[EmptyCommand]) {
        newline + emit(e.prep) + newline
      } else {
        ""
      }
      val suffix = if (!e.alt.isInstanceOf[EmptyCommand]) {
        newline + "else : " + withIndent{ newline + emit(e.alt) }
      } else {
        ""
      }
      prefix + "when " + emit(e.pred) + " : " + withIndent{ emit(e.conseq) } + suffix
    }
    case e: Begin => join0(e.body.map(x => emit(x)), newline).toString
    case e: Connect => s"${emit(e.loc)} := ${emit(e.exp)}"
    case e: BulkConnect => s"${emit(e.loc1)} <> ${emit(e.loc2)}"
    case e: ConnectInit => s"onreset ${emit(e.loc)} := ${emit(e.exp)}"
    case e: ConnectInitIndex => s"onreset ${emit(e.loc)}[${e.index}] := ${emit(e.exp)}"
    case e: EmptyCommand => "skip"
  }
  def initPort(p: Port, dir: Direction) = {
    for (x <- p.id.flatten; if x.dir == dir)
      yield s"${Builder.globalRefMap.getRefForId(x).fullname} := ${emit(x.makeLit(0).ref)}"
  }
  def emit(e: Component): String =  {
    withIndent{ "module " + e.name + " : " +
      join0(e.ports.map(x => emitPort(x, true)), newline) +
      newline + join0(e.ports.flatMap(x => initPort(x, OUTPUT)), newline) +
      newline + emit(e.body) }
  }
  def emit(e: Circuit): String = 
    withIndent{ "circuit " + e.main + " : " + join0(e.components.map(x => emit(x)), newline) } + newline
}

object emit {
  def apply(e: Circuit) = new Emitter().emit(e)
}
