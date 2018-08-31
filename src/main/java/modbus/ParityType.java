package modbus;

public enum ParityType {
	NONE, ODD, EVEN, MARK, SPACE;

	static int parseParity(String parstr) {
		int parint = 0;
		switch (parstr.toUpperCase()) {
		case "NONE":
			break;
		case "ODD":
			parint = 1;
			break;
		case "EVEN":
			parint = 2;
			break;
		case "MARK":
			parint = 3;
			break;
		case "SPACE":
			parint = 4;
			break;
		default:
			break;
		}
		return parint;
	}
}
