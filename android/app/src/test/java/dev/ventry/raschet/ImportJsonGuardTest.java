package dev.ventry.raschet;

import org.junit.Test;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import static org.junit.Assert.*;

public class ImportJsonGuardTest {
    private String read(String source) throws IOException{
        try(Reader input=new ImportJsonGuard(new StringReader(source))){
            StringBuilder text=new StringBuilder();
            char[] buffer=new char[97];int n;
            while((n=input.read(buffer))!=-1)text.append(buffer,0,n);
            return text.toString();
        }
    }
    private void reject(String value) throws Exception{
        try{read(value);fail("Unbounded JSON accepted");}catch(IOException expected){}
    }
    private static String repeat(String value,int count){
        StringBuilder result=new StringBuilder();
        for(int i=0;i<count;i++)result.append(value);
        return result.toString();
    }
    @Test public void preservesEscapesAcrossReaderBuffers() throws Exception{
        String value="{\"entries\":[{\"note\":\""+repeat("Я",76)+" \\\"{[] } \\\\ end\"}]}";
        assertEquals(value,read(value));
    }
    @Test public void boundsTokenRowAndDepth() throws Exception{
        reject("{\"entries\":[{\"note\":\""+repeat("x",8193)+"\"}]}");
        reject("{\"entries\":[{"+repeat("\"field\":\""+repeat("x",8000)+"\",",10)+"\"end\":0}]}");
        reject(repeat("[",33)+repeat("]",33));
    }
}
