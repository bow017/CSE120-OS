package nachos.userprog;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;

import java.io.EOFException;
import java.util.*;

/**
 * Encapsulates the state of a user process that is not contained in its user
 * thread (or threads). This includes its address translation state, a file
 * table, and information about the program being executed.
 * 
 * <p>
 * This class is extended by other classes to support additional functionality
 * (such as additional syscalls).
 * 
 * @see nachos.vm.VMProcess
 * @see nachos.network.NetProcess
 */
public class UserProcess {
	/**
	 * Allocate a new process.
	 */
	public UserProcess() {
		//initialize filetables
		OpenFiles = new OpenFile[16];
		OpenFiles[0] = UserKernel.console.openForReading();
		OpenFiles[1] = UserKernel.console.openForWriting();
		
		int numPhysPages = Machine.processor().getNumPhysPages();
//		pageTable = new TranslationEntry[numPhysPages];
//		for (int i = 0; i < numPhysPages; i++)
//			pageTable[i] = new TranslationEntry(i, i, true, false, false, false);
		
		//assign pid
		pid = ++pidCounter;
		
		sleepLock = new Lock();
		sleepCondition = new Condition(sleepLock);
	}

	/**
	 * Allocate and return a new process of the correct class. The class name is
	 * specified by the <tt>nachos.conf</tt> key
	 * <tt>Kernel.processClassName</tt>.
	 * 
	 * @return a new process of the correct class.
	 */
	public static UserProcess newUserProcess() {
		return (UserProcess) Lib.constructObject(Machine.getProcessClassName());
	}

	/**
	 * Execute the specified program with the specified arguments. Attempts to
	 * load the program, and then forks a thread to run it.
	 * 
	 * @param name the name of the file containing the executable.
	 * @param args the arguments to pass to the executable.
	 * @return <tt>true</tt> if the program was successfully executed.
	 */
	public boolean execute(String name, String[] args) {
		if (!load(name, args))
			return false;

		//new UThread(this).setName(name).fork();
		//modify in order to use thread.join
		thread = new UThread(this);
		thread.setName(name).fork();
		runningProcess++;

		return true;
	}

	/**
	 * Save the state of this process in preparation for a context switch.
	 * Called by <tt>UThread.saveState()</tt>.
	 */
	public void saveState() {
	}

	/**
	 * Restore the state of this process after a context switch. Called by
	 * <tt>UThread.restoreState()</tt>.
	 */
	public void restoreState() {
		Machine.processor().setPageTable(pageTable);
	}

	/**
	 * Read a null-terminated string from this process's virtual memory. Read at
	 * most <tt>maxLength + 1</tt> bytes from the specified address, search for
	 * the null terminator, and convert it to a <tt>java.lang.String</tt>,
	 * without including the null terminator. If no null terminator is found,
	 * returns <tt>null</tt>.
	 * 
	 * @param vaddr the starting virtual address of the null-terminated string.
	 * @param maxLength the maximum number of characters in the string, not
	 * including the null terminator.
	 * @return the string read, or <tt>null</tt> if no null terminator was
	 * found.
	 */
	public String readVirtualMemoryString(int vaddr, int maxLength) {
		Lib.assertTrue(maxLength >= 0);

		byte[] bytes = new byte[maxLength + 1];

		int bytesRead = readVirtualMemory(vaddr, bytes);

		for (int length = 0; length < bytesRead; length++) {
			if (bytes[length] == 0)
				return new String(bytes, 0, length);
		}

		return null;
	}

	/**
	 * Transfer data from this process's virtual memory to all of the specified
	 * array. Same as <tt>readVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 * 
	 * @param vaddr the first byte of virtual memory to read.
	 * @param data the array where the data will be stored.
	 * @return the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data) {
		return readVirtualMemory(vaddr, data, 0, data.length);
	}

	/**
	 * Transfer data from this process's virtual memory to the specified array.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no data
	 * could be copied).
	 * 
	 * @param vaddr the first byte of virtual memory to read.
	 * @param data the array where the data will be stored.
	 * @param offset the first byte to write in the array.
	 * @param length the number of bytes to transfer from virtual memory to the
	 * array.
	 * @return the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		Lib.assertTrue(offset >= 0 && length >= 0
				&& offset + length <= data.length);

		byte[] memory = Machine.processor().getMemory();

		// for now, just assume that virtual addresses equal physical addresses
		if (vaddr < 0 || vaddr >= memory.length)
			return 0;
//		int amount = Math.min(length, memory.length - vaddr);
//		System.arraycopy(memory, vaddr, data, offset, amount);
		int amount = 0;
		while(amount < length && offset < data.length) {
			int vpn = Processor.pageFromAddress(vaddr);
			if(vpn < 0 || vpn >= pageTable.length) {
				Lib.debug(dbgProcess, "readVirtualMemory: invalid vaddr(vpn out of range)");
				break;
			}
			
			pageTable[vpn].used = true;
			
			int addrOffset = Processor.offsetFromAddress(vaddr);
			int paddr = pageTable[vpn].ppn * pageSize + addrOffset;
			int transferAmount = Math.min(Math.min(length, pageSize - addrOffset), data.length - offset);
			
			System.arraycopy(memory, paddr, data, offset, transferAmount);
			
			vaddr += transferAmount;
			offset += transferAmount;
			amount += transferAmount;
		}
		return amount;
	}

	/**
	 * Transfer all data from the specified array to this process's virtual
	 * memory. Same as <tt>writeVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 * 
	 * @param vaddr the first byte of virtual memory to write.
	 * @param data the array containing the data to transfer.
	 * @return the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data) {
		return writeVirtualMemory(vaddr, data, 0, data.length);
	}

	/**
	 * Transfer data from the specified array to this process's virtual memory.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no data
	 * could be copied).
	 * 
	 * @param vaddr the first byte of virtual memory to write.
	 * @param data the array containing the data to transfer.
	 * @param offset the first byte to transfer from the array.
	 * @param length the number of bytes to transfer from the array to virtual
	 * memory.
	 * @return the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		Lib.assertTrue(offset >= 0 && length >= 0
				&& offset + length <= data.length);

		byte[] memory = Machine.processor().getMemory();

		// for now, just assume that virtual addresses equal physical addresses
		if (vaddr < 0 || vaddr >= memory.length)
			return 0;

//		int amount = Math.min(length, memory.length - vaddr);
//		System.arraycopy(data, offset, memory, vaddr, amount);
		
		int amount = 0;
		while(amount < length && offset < data.length) {
			int vpn = Processor.pageFromAddress(vaddr);
			
			if(vpn < 0 || vpn >= pageTable.length) {
				Lib.debug(dbgProcess, "readVirtualMemory: invalid vaddr(vpn out of range)");
				break;
			}
			if(pageTable[vpn].readOnly) {
				Lib.debug(dbgProcess, "readVirtualMemory: invalid vaddr(read only!)");
				return amount;
			}
			pageTable[vpn].used = true;
			pageTable[vpn].dirty = true;
			
			int addrOffset = Processor.offsetFromAddress(vaddr);
			int paddr = pageTable[vpn].ppn * pageSize + addrOffset;
			int transferAmount = Math.min(Math.min(length, pageSize - addrOffset), data.length - offset);
			
			System.arraycopy(data, offset, memory, paddr, transferAmount);
			
			vaddr += transferAmount;
			offset += transferAmount;
			amount += transferAmount;
		}
		return amount;

	}

	/**
	 * Load the executable with the specified name into this process, and
	 * prepare to pass it the specified arguments. Opens the executable, reads
	 * its header information, and copies sections and arguments into this
	 * process's virtual memory.
	 * 
	 * @param name the name of the file containing the executable.
	 * @param args the arguments to pass to the executable.
	 * @return <tt>true</tt> if the executable was successfully loaded.
	 */
	private boolean load(String name, String[] args) {
		Lib.debug(dbgProcess, "UserProcess.load(\"" + name + "\")");

		OpenFile executable = ThreadedKernel.fileSystem.open(name, false);
		if (executable == null) {
			Lib.debug(dbgProcess, "\topen failed");
			return false;
		}

		try {
			coff = new Coff(executable);
		}
		catch (EOFException e) {
			executable.close();
			Lib.debug(dbgProcess, "\tcoff load failed");
			return false;
		}

		// make sure the sections are contiguous and start at page 0
		numPages = 0;
		for (int s = 0; s < coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);
			if (section.getFirstVPN() != numPages) {
				coff.close();
				Lib.debug(dbgProcess, "\tfragmented executable");
				return false;
			}
			numPages += section.getLength();
		}

		// make sure the argv array will fit in one page
		byte[][] argv = new byte[args.length][];
		int argsSize = 0;
		for (int i = 0; i < args.length; i++) {
			argv[i] = args[i].getBytes();
			// 4 bytes for argv[] pointer; then string plus one for null byte
			argsSize += 4 + argv[i].length + 1;
		}
		if (argsSize > pageSize) {
			coff.close();
			Lib.debug(dbgProcess, "\targuments too long");
			return false;
		}

		// program counter initially points at the program entry point
		initialPC = coff.getEntryPoint();

		// next comes the stack; stack pointer initially points to top of it
		numPages += stackPages;
		initialSP = numPages * pageSize;

		// and finally reserve 1 page for arguments
		numPages++;

		if (!loadSections())
			return false;

		// store arguments in last page
		int entryOffset = (numPages - 1) * pageSize;
		int stringOffset = entryOffset + args.length * 4;

		this.argc = args.length;
		this.argv = entryOffset;

		for (int i = 0; i < argv.length; i++) {
			byte[] stringOffsetBytes = Lib.bytesFromInt(stringOffset);
			Lib.assertTrue(writeVirtualMemory(entryOffset, stringOffsetBytes) == 4);
			entryOffset += 4;
			Lib.assertTrue(writeVirtualMemory(stringOffset, argv[i]) == argv[i].length);
			stringOffset += argv[i].length;
			Lib.assertTrue(writeVirtualMemory(stringOffset, new byte[] { 0 }) == 1);
			stringOffset += 1;
		}

		return true;
	}

	/**
	 * Allocates memory for this process, and loads the COFF sections into
	 * memory. If this returns successfully, the process will definitely be run
	 * (this is the last step in process initialization that can fail).
	 * 
	 * @return <tt>true</tt> if the sections were successfully loaded.
	 */
	protected boolean loadSections() {
//		if (numPages > Machine.processor().getNumPhysPages()) {
//			coff.close();
//			Lib.debug(dbgProcess, "\tinsufficient physical memory");
//			return false;
//		}
		
//		if(numPages > UserKernel.freePages.size()) {//should check each time!!!
//			coff.close();
//			Lib.debug(dbgProcess, "\tinsufficient physical memory");
//			return false;
//		}
		//allocate physical pages to current process
		pageTable = new TranslationEntry[numPages];
		for (int i = 0; i < numPages; i++) {
			int ppn = UserKernel.allocate();
			if(ppn == -1) {
				coff.close();
				Lib.debug(dbgProcess, "\tloadSections: insufficient physical memory");
				return false;
			}
			pageTable[i] = new TranslationEntry(i, ppn, true, false, false, false);
			
		}
			

		// load sections
		for (int s = 0; s < coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);

			Lib.debug(dbgProcess, "\tinitializing " + section.getName()
					+ " section (" + section.getLength() + " pages)");

			for (int i = 0; i < section.getLength(); i++) {
				int vpn = section.getFirstVPN() + i;
				//check if read only
				if(section.isReadOnly()) {
					pageTable[vpn].readOnly = true;
				}
				
				// for now, just assume virtual addresses=physical addresses
				//section.loadPage(i, vpn);
				
				//find ppn
				int ppn = pageTable[vpn].ppn;
				section.loadPage(i, ppn);
				
			}
		}

		return true;
	}

	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 */
	protected void unloadSections() {
		for(int i = 0; i < numPages; i++) {
			UserKernel.deallocate(pageTable[i].ppn);
		}
	}

	/**
	 * Initialize the processor's registers in preparation for running the
	 * program loaded into this process. Set the PC register to point at the
	 * start function, set the stack pointer register to point at the top of the
	 * stack, set the A0 and A1 registers to argc and argv, respectively, and
	 * initialize all other registers to 0.
	 */
	public void initRegisters() {
		Processor processor = Machine.processor();

		// by default, everything's 0
		for (int i = 0; i < processor.numUserRegisters; i++)
			processor.writeRegister(i, 0);

		// initialize PC and SP according
		processor.writeRegister(Processor.regPC, initialPC);
		processor.writeRegister(Processor.regSP, initialSP);

		// initialize the first two argument registers to argc and argv
		processor.writeRegister(Processor.regA0, argc);
		processor.writeRegister(Processor.regA1, argv);
	}

	/**
	 * Handle the halt() system call.
	 */
	private int handleHalt() {

		Machine.halt();

		Lib.assertNotReached("Machine.halt() did not halt machine!");
		return 0;
	}
	
	private void handleExit(int status) {
		for(int i = 0; i < OpenFiles.length; i++) {
			if(OpenFiles[i] != null) {
				OpenFiles[i].close();
			}
		}
		//delete all memory
		unloadSections();
		coff.close();
		if(parent != null) {
			parent.childrenStatus.put(pid, new Integer(status));
			parent.sleepLock.acquire();
			parent.sleepCondition.wake();
			parent.sleepLock.release();
		}
		if(--runningProcess == 0) {
			UserKernel.kernel.terminate();
		}
		KThread.finish();
		
		return;
	}
	
	private int handleExec(int nameVaddr, int argc, int argv) {
		if(nameVaddr < 0 || argc < 0 || argv < 0) {
			Lib.debug(dbgProcess, "handleExec: Invalid input");
			return -1;
		}
		String fileName = readVirtualMemoryString(nameVaddr, 256);
		if(fileName == null) {
			Lib.debug(dbgProcess, "handleExec: fail to read filename");
			return -1;
		}
		if(!fileName.substring(fileName.length() - 5, fileName.length()).equals(".coff")) {
			Lib.debug(dbgProcess, "handleExec: file type should be .coff");
			return -1;
		}
		
		String[] args = new String[argc];
		for(int i = 0; i < argc; i++) {
			byte[] argBuffer = new byte[4];//sizeof(int)
			int bytesRead = readVirtualMemory(argv + i * 4, argBuffer);
			if(bytesRead != 4) {
				Lib.debug(dbgProcess, "handleExec: fail to read current arg pointer");
				return -1;
			}
			int argVaddr = Lib.bytesToInt(argBuffer, 0);
			String arg = readVirtualMemoryString(argVaddr, 256);
			if(arg == null) {
				Lib.debug(dbgProcess, "handleExec: fail to read current arg");
				return -1;
			}
			args[i] = arg;
		}
		
		UserProcess child = newUserProcess();
		if(!child.execute(fileName, args)) {
			Lib.debug(dbgProcess, "handleExec: fail to execute child process");
			return -1;
		}
		child.parent = this;
		childrenList.add(child);
		
		
		return child.pid;
	}
	
	private int handleJoin(int pid, int statusVaddr) {
		//find the child with specific pid
		UserProcess child = null;
		for(UserProcess u : childrenList) {
			if(u.pid == pid) {
				child = u;
				break;
			}
		}
		if(child == null) {
			Lib.debug(dbgProcess, "handleJoin: no child with this pid");
			return -1;
		}
			
		//assume one process only has one thread
		//child.thread.join();
		
		//condition variable
		//if child process exit abnormally, nachos exit then everything break down?
		sleepLock.acquire();
		while(!childrenStatus.containsKey(pid)) {
			sleepCondition.sleep();
		}
		sleepLock.release();
		
		
		Integer status = childrenStatus.get(pid);
		
		if(status == null) {
			Lib.debug(dbgProcess, "handleJoin: the child exited as a result of an unhandled exception");
			return 0;
		}
		byte[] buffer = new byte[4];
		buffer = Lib.bytesFromInt(status);
		if(writeVirtualMemory(statusVaddr, buffer) != 4) {
			Lib.debug(dbgProcess, "handleJoin: fail to write child status to virtual memory");
			return -1;
		}
		return 1;
	}
	
	private int handleCreate(int nameVaddr) {
		//check if there is room
		int newRoom = -1;
		for(int i = 0; i < 16; i++) {
			if(OpenFiles[i] == null) {
				newRoom = i;
				break;
			}
		}
		if(newRoom == -1) {
			Lib.debug(dbgProcess, "handleCreate: no room for this file");
			return -1;
		}
		
		//check if nameVaddr is valid
//		if(nameVaddr < 0) {
//			Lib.debug(dbgProcess, "handleCreate: invalid virtual address");
//			return -1;
//		}
		String fileName = readVirtualMemoryString(nameVaddr, 256);
		if(fileName == null) {
			Lib.debug(dbgProcess, "handleCreate: fail to read filename");
			return -1;
		}
		//different from open
		OpenFile file = ThreadedKernel.fileSystem.open(fileName, true);
		if(file == null) {
			Lib.debug(dbgProcess, "handleCreate: fail to open file");
			return -1;
		}
		OpenFiles[newRoom] = file;
		return newRoom;
	}
	
	private int handleOpen(int nameVaddr) {
		//check if there is room
		int newRoom = -1;
		for(int i = 0; i < 16; i++) {
			if(OpenFiles[i] == null) {
				newRoom = i;
				break;
			}
		}
		if(newRoom == -1) {
			Lib.debug(dbgProcess, "handleOpen: no room for this file");
			return -1;
		}
		
		//check if nameVaddr is valid, (but readVirtualMemory will do the checking)
//		if(nameVaddr < 0) {
//			Lib.debug(dbgProcess, "handleOpen: invalid virtual address");
//			return -1;
//		}
		String fileName = readVirtualMemoryString(nameVaddr, 256);
		if(fileName == null) {
			Lib.debug(dbgProcess, "handleOpen: fail to read filename");
			return -1;
		}
		OpenFile file = ThreadedKernel.fileSystem.open(fileName, false);
		if(file == null) {
			Lib.debug(dbgProcess, "handleOpen: fail to open file");
			return -1;
		}
		OpenFiles[newRoom] = file;
		return newRoom;
	}
	
	private int handleRead(int fileDescriptor, int bufferVaddr, int count) {
		if(fileDescriptor < 0 || fileDescriptor > 15) {
			Lib.debug(dbgProcess, "handleRead: fileDescriptor should be within [0, 15]");
			return -1;
		}
		if(OpenFiles[fileDescriptor] == null) {
			Lib.debug(dbgProcess, "handleRead: file doesn't exist");
			return -1;
		}
		OpenFile readFile = OpenFiles[fileDescriptor];
//		if(bufferVaddr < 0) { writevirtualmemory will check vaddr
//			Lib.debug(dbgProcess, "handleRead: invalid buffer virtual address");
//			return -1;
//		}
		if(count < 0) {
			Lib.debug(dbgProcess, "handleRead: requested number of bytes can't be nagetive");
			return -1;
		}
		int bytesTransfer = 0;
		byte[] dummyBuffer = new byte[pageSize];
		
		//break the loop if finish transferring all bytes requested or reach to the end of readfile
		while(count > 0) {
			int tryRead = Math.min(pageSize, count);
			int actualRead = readFile.read(dummyBuffer, 0, tryRead);
			if(actualRead == -1) {
				Lib.debug(dbgProcess, "handleRead: fail to read from file ");
				return -1;
			}
			int bytesWritten = writeVirtualMemory(bufferVaddr, dummyBuffer, 0, actualRead);
			if(bytesWritten != actualRead) {
				Lib.debug(dbgProcess, "handleRead: fail to write all data to buffer virtual memory ");
				bytesTransfer += bytesWritten;
				return bytesTransfer;
			}
			count -= actualRead;
			bytesTransfer += actualRead;
			bufferVaddr += actualRead;
			//arrive to end of file or encounter read-only parts
			if(actualRead < tryRead) {
				break;
			}
		}
		
		return bytesTransfer;
	}
	
	private int handleWrite(int fileDescriptor, int bufferVaddr, int count) {
		if(fileDescriptor < 0 || fileDescriptor > 15) {
			Lib.debug(dbgProcess, "handleWrite: fileDescriptor should be within [0, 15]");
			return -1;
		}
		if(OpenFiles[fileDescriptor] == null) {
			Lib.debug(dbgProcess, "handleWrite: file doesn't exist");
			return -1;
		}
		OpenFile writeFile = OpenFiles[fileDescriptor];
		if(count < 0) {
			Lib.debug(dbgProcess, "handleWrite: requested number of bytes can't be nagetive");
			return -1;
		}
		int bytesTransfer = 0;
		byte[] dummyBuffer = new byte[pageSize];
		while(count > 0) {
			int tryWrite = Math.min(pageSize, count);
			int bytesRead = readVirtualMemory(bufferVaddr, dummyBuffer, 0, tryWrite);
			if(bytesRead < tryWrite) {
				Lib.debug(dbgProcess, "handleWrite: fail to read from virtual memory");
				return -1;
			}
			int actualWrite = writeFile.write(dummyBuffer, 0, tryWrite);
			if(actualWrite < tryWrite) {
				Lib.debug(dbgProcess, "handleWrite: fail to write to file");
				return -1;
			}
			
			count -= tryWrite;
			bytesTransfer += tryWrite;
			bufferVaddr += tryWrite;
		}
		
		return bytesTransfer;
	}
	
	private int handleClose(int fileDescriptor) {
		if(fileDescriptor < 0 || fileDescriptor > 15) {
			Lib.debug(dbgProcess, "handleClose: fileDescriptor should be within [0, 15]");
			return -1;
		}
		OpenFile closeFile = OpenFiles[fileDescriptor];
		if(closeFile == null) {
			Lib.debug(dbgProcess, "handleClose: no such file exists");
			return -1;
		}
		closeFile.close();
		OpenFiles[fileDescriptor] = null;
		return 0;
	}
	
	private int handleUnlink(int nameVaddr) {
		String fileName =  readVirtualMemoryString(nameVaddr, 256);
		if(fileName == null) {
			Lib.debug(dbgProcess, "handleUnlink: fail to read filename");
			return -1;
		}
		//check if the file is closed already
		for(int i = 0; i < 16; i++) {
			if(OpenFiles[i] != null && OpenFiles[i].getName().equals(fileName)) {
				Lib.debug(dbgProcess, "handleUnlink: close the file before unlink");
				return -1;
			}
		}
		if(!ThreadedKernel.fileSystem.remove(fileName)) {
			Lib.debug(dbgProcess, "handleUnlink: fail to remove file");
			return -1;
		}
		return 0;
	}
	private static final int syscallHalt = 0, syscallExit = 1, syscallExec = 2,
			syscallJoin = 3, syscallCreate = 4, syscallOpen = 5,
			syscallRead = 6, syscallWrite = 7, syscallClose = 8,
			syscallUnlink = 9;

	/**
	 * Handle a syscall exception. Called by <tt>handleException()</tt>. The
	 * <i>syscall</i> argument identifies which syscall the user executed:
	 * 
	 * <table>
	 * <tr>
	 * <td>syscall#</td>
	 * <td>syscall prototype</td>
	 * </tr>
	 * <tr>
	 * <td>0</td>
	 * <td><tt>void halt();</tt></td>
	 * </tr>
	 * <tr>
	 * <td>1</td>
	 * <td><tt>void exit(int status);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>2</td>
	 * <td><tt>int  exec(char *name, int argc, char **argv);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>3</td>
	 * <td><tt>int  join(int pid, int *status);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>4</td>
	 * <td><tt>int  creat(char *name);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>5</td>
	 * <td><tt>int  open(char *name);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>6</td>
	 * <td><tt>int  read(int fd, char *buffer, int size);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>7</td>
	 * <td><tt>int  write(int fd, char *buffer, int size);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>8</td>
	 * <td><tt>int  close(int fd);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>9</td>
	 * <td><tt>int  unlink(char *name);</tt></td>
	 * </tr>
	 * </table>
	 * 
	 * @param syscall the syscall number.
	 * @param a0 the first syscall argument.
	 * @param a1 the second syscall argument.
	 * @param a2 the third syscall argument.
	 * @param a3 the fourth syscall argument.
	 * @return the value to be returned to the user.
	 */
	public int handleSyscall(int syscall, int a0, int a1, int a2, int a3) {
		switch (syscall) {
		case syscallHalt:
			return handleHalt();
		case syscallExit:
			 handleExit(a0);
			 Lib.assertNotReached("fail to exit");
			 return 0;
		case syscallExec:
			return handleExec(a0, a1, a2);
		case syscallJoin:
			return handleJoin(a0, a1);
		case syscallCreate:
			return handleCreate(a0);
		case syscallOpen:
			return handleOpen(a0);
		case syscallRead:
			return handleRead(a0, a1, a2);
		case syscallWrite:
			return handleWrite(a0, a1, a2);
		case syscallClose:
			return handleClose(a0);
		case syscallUnlink:
			return handleUnlink(a0);
		default:
			Lib.debug(dbgProcess, "Unknown syscall " + syscall);
			Lib.assertNotReached("Unknown system call!");
		}
		return 0;
	}

	/**
	 * Handle a user exception. Called by <tt>UserKernel.exceptionHandler()</tt>
	 * . The <i>cause</i> argument identifies which exception occurred; see the
	 * <tt>Processor.exceptionZZZ</tt> constants.
	 * 
	 * @param cause the user exception that occurred.
	 */
	public void handleException(int cause) {
		Processor processor = Machine.processor();

		switch (cause) {
		case Processor.exceptionSyscall:
			int result = handleSyscall(processor.readRegister(Processor.regV0),
					processor.readRegister(Processor.regA0),
					processor.readRegister(Processor.regA1),
					processor.readRegister(Processor.regA2),
					processor.readRegister(Processor.regA3));
			processor.writeRegister(Processor.regV0, result);
			processor.advancePC();
			break;

		default:
			Lib.debug(dbgProcess, "Unexpected exception: "
					+ Processor.exceptionNames[cause]);
			handleHalt();
			Lib.assertNotReached("Unexpected exception");
		}
	}

	/** The program being run by this process. */
	protected Coff coff;

	/** This process's page table. */
	protected TranslationEntry[] pageTable;

	/** The number of contiguous pages occupied by the program. */
	protected int numPages;

	/** The number of pages in the program's stack. */
	protected final int stackPages = 8;

	protected OpenFile[] OpenFiles;
	
	private static int pidCounter = 0;
	
	private static int runningProcess = 0;
	
	private int pid;
	
	private UserProcess parent = null;
	
	private LinkedList<UserProcess> childrenList = new LinkedList<UserProcess>();
	
	//store the exit status of child processes
	private HashMap<Integer, Integer> childrenStatus = new HashMap<Integer, Integer>();
	
	private UThread thread;

	private int initialPC, initialSP;

	private int argc, argv;

	private static final int pageSize = Processor.pageSize;

	private static final char dbgProcess = 'a';
	
	private Lock sleepLock;
	
	private Condition sleepCondition;
	
}
