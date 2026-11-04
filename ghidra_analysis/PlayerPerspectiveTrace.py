# Ghidra headless script: locate POV metadata references and decompile callers.
import re
import struct
import jarray

POV = re.compile(
    r"(?i)(Camera(FirstPerson|ThirdPerson|PerspectiveOption|RenderFirstPersonObjects|"
    r"FirstPersonClimbing|ThirdPersonBoom|ThirdPersonFixedBoom|SpecialCameraMode)|"
    r"minecraft:camera_(first_person|third_person)|minecraft:(first_person|third_person|"
    r"third_person_front)|playerViewPerspective|key\.togglePerspective|"
    r"button\.toggle_perspective|options\.thirdperson|commands\.camera\.perspective|"
    r"camera_third_person_boom|camera_first_person|firstperson_radio|thirdperson_radio|"
    r"variable\.is_first_person|query\.is_first_person|"
    r"FirstPersonPlayerShadowsEnabled|THIRD_PERSON)"
)


def scan_strings():
    found = {}
    memory = currentProgram.getMemory()
    for block in memory.getBlocks():
        if block.isExecute() or not block.isInitialized():
            continue
        offset = 0
        while offset < block.getSize():
            monitor.checkCancelled()
            length = min(1 << 20, block.getSize() - offset)
            buf = jarray.zeros(length, "b")
            memory.getBytes(block.getStart().add(offset), buf, 0, length)
            raw = "".join(chr(b & 0xff) for b in buf)
            for match in re.finditer(r"[\x20-\x7e]{5,1024}", raw):
                value = match.group(0)
                if POV.search(value):
                    address = block.getStart().getOffset() + offset + match.start()
                    found[address] = value
            offset += length
    for address, value in sorted(found.items()):
        println("POV_STRING\t%s\t%s" % (
            currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(address),
            value))
    println("POV strings found: %d" % len(found))
    return found


def scan_references(strings):
    pages = set(address & ~0xfff for address in strings)
    references = {}
    memory = currentProgram.getMemory()
    for block in memory.getBlocks():
        if not block.isExecute() or not block.isInitialized():
            continue
        offset = 0
        while offset + 16 <= block.getSize():
            monitor.checkCancelled()
            length = min(1 << 20, block.getSize() - offset)
            length &= ~3
            buf = jarray.zeros(length, "b")
            memory.getBytes(block.getStart().add(offset), buf, 0, length)
            raw = "".join(chr(b & 0xff) for b in buf)
            for i in range(0, length - 12, 4):
                adrp = struct.unpack_from("<I", raw, i)[0]
                if adrp & 0x9f000000 != 0x90000000:
                    continue
                immediate = (((adrp >> 5) & 0x7ffff) << 2) | ((adrp >> 29) & 3)
                if immediate & (1 << 20):
                    immediate |= ~((1 << 21) - 1)
                pc = block.getStart().getOffset() + offset + i
                page = (pc & ~0xfff) + (immediate << 12)
                if page not in pages:
                    continue
                base_register = adrp & 0x1f
                for step in range(1, 4):
                    add = struct.unpack_from("<I", raw, i + step * 4)[0]
                    if add & 0x7f000000 != 0x11000000 or ((add >> 5) & 0x1f) != base_register:
                        continue
                    addend = (add >> 10) & 0xfff
                    if (add >> 22) & 1:
                        addend <<= 12
                    target = page + addend
                    if target not in strings:
                        continue
                    reference = pc + step * 4
                    references[reference] = strings[target]
                    println("POV_XREF\t0x%x\t%s" % (reference, strings[target]))
                    break
            offset += length
    println("Direct code references found: %d" % len(references))
    return references


def prologue_before(address):
    memory = currentProgram.getMemory()
    block = memory.getBlock(address)
    if block is None:
        return None
    offset = address.subtract(block.getStart())
    floor = max(0, offset - 0x4000)
    pos = offset & ~3
    while pos >= floor:
        ins = memory.getInt(block.getStart().add(pos)) & 0xffffffff
        masked = ins & 0xffc07fff
        if masked in (0xa9807bfd, 0xa9007bfd) or ins & 0xffc003ff == 0xd10003ff:
            return block.getStart().add(pos)
        pos -= 4
    return None


def decompile_callers(references):
    manager = currentProgram.getFunctionManager()
    candidates = {}
    for offset, label in references.items():
        address = currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(offset)
        function = manager.getFunctionContaining(address)
        if function is not None:
            candidates[function.getEntryPoint().getOffset()] = label
        else:
            start = prologue_before(address)
            if start is not None:
                candidates[start.getOffset()] = label

    decompiler = ghidra.app.decompiler.DecompInterface()
    decompiler.openProgram(currentProgram)
    completed = 0
    try:
        for offset, label in sorted(candidates.items()):
            if completed >= 24:
                break
            address = currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(offset)
            function = manager.getFunctionAt(address)
            if function is None:
                disassemble(address)
                function = createFunction(address, "POV_candidate_%x" % offset)
            if function is None:
                println("DECOMPILE_FAILED\t%s\tno function" % address)
                continue
            result = decompiler.decompileFunction(function, 45, monitor)
            if not result.decompileCompleted() or result.getDecompiledFunction() is None:
                println("DECOMPILE_FAILED\t%s\t%s" % (address, result.getErrorMessage()))
                continue
            code = result.getDecompiledFunction().getC()
            println("POV_DECOMPILE\t%s\t%s\n%s" % (address, label, code[:18000]))
            completed += 1
    finally:
        decompiler.dispose()
    println("Candidate functions decompiled: %d" % completed)


def trace_string_references(address_strings):
    manager = currentProgram.getFunctionManager()
    references = currentProgram.getReferenceManager()
    candidates = {}
    for address_string in address_strings:
        address = currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(
            int(address_string, 0))
        iterator = references.getReferencesTo(address)
        count = 0
        while iterator.hasNext():
            reference = iterator.next()
            caller = manager.getFunctionContaining(reference.getFromAddress())
            println("POV_STRING_XREF\t%s\t%s\t%s" % (
                address, reference.getFromAddress(),
                caller.getEntryPoint() if caller is not None else "<no-function>"))
            if caller is not None:
                candidates[caller.getEntryPoint().getOffset()] = address_string
            count += 1
        if count == 0:
            println("POV_STRING_NO_XREF\t%s" % address)
    decompile_callers(candidates)


script_args = getScriptArgs()
if script_args:
    if script_args[0].startswith("s:"):
        trace_string_references([arg[2:] for arg in script_args])
    else:
        references = {}
        for arg in script_args:
            references[int(arg, 0)] = "manual reference"
        decompile_callers(references)
else:
    strings = scan_strings()
    references = scan_references(strings)
    decompile_callers(references)
