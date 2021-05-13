#include <iostream>
#include <bits/stdc++.h>
using namespace std;


int main() {
  map<pair<string,string>,bool> ab_map;
  string a; string b;
  while(getline(cin, a)){
	  	getline(cin, b);
	  	pair<string,string> temp;
	  	if( (a.compare(b)) < 0){
	  		temp.first = a; temp.second = b;
	  	}
	  	else{
	  		temp.first = b; temp.second = a;
	  	}
	  	if(ab_map.find(temp)==ab_map.end()){
	  		cout << a << endl << b << endl;
		  	ab_map[temp] = true;
	  	}
  } 
  cout << ab_map.size() << endl;
  return 0;
}
