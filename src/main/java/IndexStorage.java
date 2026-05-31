import java.io.*;

/**
 * @author Serheiev Maksym
 */
public class IndexStorage {
    public static void save(Object index, String filename){
        try(ObjectOutputStream oos = new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(filename)))){
            oos.writeObject(index);
            System.out.println("Index saved successfully in "+filename);
        }catch(IOException e){
            System.err.println("Error saving index "+e.getMessage());
        }
    }

    public static Object load(String filename){
        try(ObjectInputStream ois = new ObjectInputStream(new BufferedInputStream(new FileInputStream(filename)))){
            System.out.println("Loaded index from cache successfully: "+filename);
            return ois.readObject();
        }catch(IOException | ClassNotFoundException e){
            return null;
        }
    }
}
