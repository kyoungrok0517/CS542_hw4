import org.junit.Before;
import org.junit.Test;

public class ByteConverterTest {
	private BiDirLabel edgeValue;

	@Before
	public void setUp() throws Exception {
		edgeValue = new BiDirLabel();
		edgeValue.setMyLabel(10, 100, 10);
	}

	@Test
	public void testBiDirLabelConverter() {
		System.out.println(edgeValue);

		BiDirLabelConverter converter = new BiDirLabelConverter();
		byte[] array = new byte[20];
		converter.setValue(array, edgeValue);
		BiDirLabel newEdgeValue = converter.getValue(array);
		
		System.out.println(newEdgeValue);
		
	}

}