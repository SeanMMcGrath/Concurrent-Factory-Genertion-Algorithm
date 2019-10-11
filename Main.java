import java.io.*;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;


/**
 * @Author - Sean McGrath
 */

public class Main extends Application {//is it waking up while putting in queue??????????????????
  //  GeneticAlgorithm g = new GeneticAlgorithm(4);
       // g.run();

    public static void main (String [] args)  {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) throws IOException{


        Parent root = FXMLLoader.load(getClass().getResource("GA.fxml"));
        primaryStage.setTitle("csc375_hw01");
        primaryStage.setScene(new Scene(root, 800, 600));
        primaryStage.show();

    }
}

